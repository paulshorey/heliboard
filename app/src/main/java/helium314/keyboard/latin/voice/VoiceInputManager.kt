// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.settings.TranscriptionPreferences.GeminiConfig
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Manages the voice input workflow:
 *
 * 1. Record audio locally via [VoiceRecorder] (starts instantly).
 * 2. Stream raw PCM chunks to the Gemini Live API over WebSocket.
 * 3. Receive finalized transcripts from Gemini in stream order.
 * 4. Deliver transcript text to [VoiceInputListener.onTranscriptionResult].
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"

        private const val MIN_CHUNK_SILENCE_SECONDS = 1
        private const val MAX_CHUNK_SILENCE_SECONDS = 30
        private const val MIN_AUTO_STOP_SILENCE_SECONDS = 5
        private const val MAX_AUTO_STOP_SILENCE_SECONDS = 300
        private const val MIN_SILENCE_THRESHOLD = 40
        private const val MAX_SILENCE_THRESHOLD = 5000

        /** Maximum buffered raw PCM chunks while waiting for socket readiness. */
        private const val MAX_PENDING_AUDIO_CHUNKS = 300

        /** Maximum finalized transcripts waiting to be forwarded to the listener. */
        private const val MAX_PENDING_TRANSCRIPTS = 64

        private const val MAX_STREAM_RECONNECT_ATTEMPTS = 3
        private const val STREAM_RECONNECT_BASE_DELAY_MS = 500L
        private const val STREAM_CONNECT_TIMEOUT_MS = 12_000L

        /**
         * How long to wait for Gemini to respond after audio is sent or a turn
         * finalize is requested. Logged as VOICE_RESPONSE in the voice
         * diagnostics export when exceeded.
         *
         * Generous on purpose: the session is configured to prefer a correct
         * transcript over a fast one, so a slow reply is expected behaviour and
         * this timer only exists to surface a genuinely dead stream.
         */
        private const val GEMINI_RESPONSE_TIMEOUT_MS = 15_000L

        /**
         * Grace period subtracted from a `goAway` notice so the replacement
         * session is open before the old connection is terminated.
         */
        private const val SESSION_ROTATE_LEAD_MS = 1_500L
    }

    private data class AwaitingGeminiResponse(
        val sessionId: Long,
        val reason: String,
        val startedAtMs: Long,
        val timeoutMs: Long,
    )

    enum class State {
        IDLE,       // Not doing anything
        RECORDING,  // Actively recording (microphone is live)
        PAUSED      // Recording paused by user
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)

        /** A transcript unit was finalized — process and insert this text. */
        fun onTranscriptionResult(text: String, attachesToPrevious: Boolean)

        /** Voice processing is actively running (transcripts are pending delivery). */
        fun onProcessingStarted()

        /** No queued transcription work remains at manager level. */
        fun onProcessingIdle()

        /** Transcripts queued for the previous session were dropped (cancel, new session, etc.). */
        fun onPendingProcessingCancelled()

        fun onError(error: String)
        fun onPermissionRequired()
    }

    /**
     * Supplies the most recent editor text before the cursor, used to seed
     * Gemini's `customVocabulary` with words the user has already typed. Called
     * on the main thread from [startStreamingSession], including reconnects, so
     * callers should return the freshest available text. Returning null or a
     * blank string omits the editor-derived terms.
     */
    fun interface PriorTextProvider {
        fun getPriorText(): String?
    }

    private data class PendingAudioChunk(
        val sessionId: Long,
        val pcmData: ByteArray
    )

    private data class PendingTranscript(
        val sessionId: Long,
        val text: String,
        val attachesToPrevious: Boolean
    )

    private val voiceRecorder = VoiceRecorder(context)
    private val transcriptionClient = GeminiTranscriptionClient()
    private var listener: VoiceInputListener? = null
    private var priorTextProvider: PriorTextProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentState = State.IDLE
    private var activeSessionId = 0L

    // Local speech-boundary detection window used by VoiceRecorder callbacks.
    // Gemini segments turns server-side; local silence drives an early turn
    // finalize at speech boundaries and auto-stop after a longer pause.
    private var chunkSilenceDurationMs = Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS * 1000L
    private var chunkSilenceThreshold = Defaults.PREF_VOICE_SILENCE_THRESHOLD.toDouble()
    private var autoStopSilenceMs = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS * 1000L
    private var geminiConfig: GeminiConfig = TranscriptionPreferences.readGeminiConfig(context.prefs())

    // Streaming state
    private var streamSessionId = 0L
    private var isStreamingReady = false
    private var isStreamingConnecting = false
    private var finalizeWhenStreamReady = false
    private var isSessionStopping = false
    private var sessionApiKey = ""
    private var streamReconnectAttempts = 0
    private var pendingReconnectRunnable: Runnable? = null
    private var pendingStreamConnectTimeoutRunnable: Runnable? = null
    private var pendingSessionRotateRunnable: Runnable? = null

    // Buffered audio while stream is not yet open
    private val pendingAudioChunks = ArrayDeque<PendingAudioChunk>()

    // Finalized transcript delivery queue (strict FIFO)
    private val pendingTranscripts = ArrayDeque<PendingTranscript>()
    private var isDispatchingTranscripts = false

    // Local-VAD-driven early turn finalize. Gemini's own end-of-speech detection
    // is deliberately configured to be patient so mid-sentence pauses do not
    // split an utterance, which means a trailing phrase can sit unfinalized. When
    // the recorder reports local silence we send `audioStreamEnd` so the tail is
    // committed. Fires once per speech-stop transition, re-armed on the next
    // speech onset.
    private var hasFinalizedCurrentSilence = false

    // Tracks round-trip latency to Gemini (stream connect, audio, finalize).
    private var awaitingGeminiResponse: AwaitingGeminiResponse? = null
    private var geminiResponseTimeoutRunnable: Runnable? = null
    private var streamConnectStartedAtMs: Long = 0L

    // New paragraph timer removed — inserting line breaks on silence caused
    // form submissions and other unintended side effects in host apps.

    // Auto-stop timer — stop recording after prolonged silence (no speech)
    private val autoStopSilenceRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(
                TAG,
                "Auto-stop timer fired after ${autoStopSilenceMs}ms of silence — stopping recording"
            )
            stopRecording()
        }
    }

    val isRecording: Boolean get() = currentState == State.RECORDING
    val isPaused: Boolean get() = currentState == State.PAUSED
    val isIdle: Boolean
        get() = currentState == State.IDLE &&
            !voiceRecorder.isCurrentlyRecording &&
            !isStreamingConnecting
    val state: State get() = currentState

    fun hasPendingProcessing(): Boolean {
        return pendingTranscripts.isNotEmpty() ||
            pendingAudioChunks.isNotEmpty() ||
            isStreamingConnecting ||
            finalizeWhenStreamReady
    }

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
    }

    /**
     * Register a provider that returns the editor text before the cursor, used
     * to seed Gemini's `customVocabulary`. The provider is invoked synchronously
     * on the main thread when a streaming session opens (including reconnects),
     * so it must be cheap and must not block.
     */
    fun setPriorTextProvider(provider: PriorTextProvider?) {
        this.priorTextProvider = provider
    }

    /** Toggle: IDLE → start, RECORDING → stop, PAUSED → resume. */
    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PAUSED -> resumeRecording()
        }
    }

    /**
     * Start recording. Microphone starts immediately; Gemini connects in parallel.
     */
    fun startRecording(): Boolean {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Cannot start recording, current state: $currentState")
            return false
        }
        Log.i(TAG, "VOICE_STEP_1 start recording requested")
        if (!voiceRecorder.hasRecordPermission()) {
            listener?.onPermissionRequired()
            return false
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            listener?.onError("Gemini API key not configured. Please set it in Settings.")
            return false
        }

        reloadRuntimeConfig()
        beginNewSession()
        val sessionId = activeSessionId
        sessionApiKey = apiKey

        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                if (sessionId != activeSessionId) return
                Log.i(TAG, "VOICE_STEP_1 recording callback received")
                // State and auto-stop timer are set synchronously after
                // voiceRecorder.startRecording() returns.
            }

            override fun onAudioChunk(pcmData: ByteArray) {
                if (sessionId != activeSessionId) return
                onAudioChunkCaptured(pcmData, sessionId)
            }

            override fun onSpeechStarted() {
                if (sessionId != activeSessionId) return
                cancelAutoStopTimer()
                // New speech means a new phrase is forming; allow the next
                // silence to trigger another turn finalize.
                hasFinalizedCurrentSilence = false
            }

            override fun onSpeechStopped() {
                if (sessionId != activeSessionId) return
                startAutoStopTimer()
                requestTurnFinalizeOnSilence(sessionId)
            }

            override fun onRecordingStopped() {
                if (sessionId != activeSessionId) return
                Log.i(TAG, "Audio recording stopped")
            }

            override fun onRecordingError(error: String) {
                if (sessionId != activeSessionId) return
                Log.e(TAG, "Recording error: $error")
                stopRecordingInternal(cancelPending = true)
                listener?.onError(error)
            }
        })

        startStreamingSession(sessionId, apiKey)

        if (!voiceRecorder.startRecording()) {
            Log.e(TAG, "Failed to start audio recording")
            stopRecordingInternal(cancelPending = true)
            listener?.onError("Failed to start recording")
            return false
        }

        // Enter RECORDING immediately after AudioRecord starts so lifecycle guards
        // don't treat this startup window as idle.
        updateState(State.RECORDING)
        startAutoStopTimer()

        return true
    }

    fun stopRecording() {
        if (isIdle) return
        Log.i(TAG, "Stopping recording")
        stopRecordingInternal(cancelPending = false)
    }

    fun cancelRecording() {
        Log.i(TAG, "Cancelling recording")
        stopRecordingInternal(cancelPending = true)
    }

    fun pauseRecording() {
        if (currentState != State.RECORDING) return
        cancelAutoStopTimer()
        voiceRecorder.pauseRecording()
        cancelPendingReconnect()
        streamReconnectAttempts = 0
        finalizeWhenStreamReady = false
        // A turn left open with no incoming audio is what makes the Live API
        // drop the connection, so close it out before the mic goes quiet.
        if (isStreamingReady) {
            transcriptionClient.finalizeTurn()
        }
        updateState(State.PAUSED)
    }

    fun resumeRecording() {
        if (currentState != State.PAUSED) return
        if (!isStreamingReady && !isStreamingConnecting && sessionApiKey.isNotBlank()) {
            startStreamingSession(activeSessionId, sessionApiKey, isReconnect = true)
        }
        voiceRecorder.resumeRecording()
        updateState(State.RECORDING)
        startAutoStopTimer()
    }

    fun togglePause() {
        when (currentState) {
            State.RECORDING -> pauseRecording()
            State.PAUSED -> resumeRecording()
            else -> {}
        }
    }

    fun destroy() {
        stopRecordingInternal(cancelPending = true)
        listener = null
    }

    // ── Private helpers ────────────────────────────────────────────────

    private fun beginNewSession() {
        invalidateActiveSession("new recording session")
    }

    private fun invalidateActiveSession(reason: String) {
        val hadPendingWork = hasPendingProcessing()
        activeSessionId += 1
        streamSessionId = 0L
        isStreamingReady = false
        isStreamingConnecting = false
        finalizeWhenStreamReady = false
        isSessionStopping = false
        sessionApiKey = ""
        streamReconnectAttempts = 0
        cancelPendingReconnect()
        cancelStreamConnectTimeout()
        cancelSessionRotateTimer()
        cancelGeminiResponseWatchdog()
        streamConnectStartedAtMs = 0L
        pendingAudioChunks.clear()
        pendingTranscripts.clear()
        isDispatchingTranscripts = false
        hasFinalizedCurrentSilence = false
        transcriptionClient.cancelAll()
        if (hadPendingWork) {
            listener?.onPendingProcessingCancelled()
        }
        notifyProcessingIdleIfDrained()
        Log.i(TAG, "Voice session invalidated ($reason), sessionId=$activeSessionId")
    }

    private fun stopRecordingInternal(cancelPending: Boolean) {
        cancelAutoStopTimer()
        cancelStreamConnectTimeout()
        cancelSessionRotateTimer()

        val sessionAtStop = activeSessionId
        if (cancelPending) {
            invalidateActiveSession("recording cancelled")
        } else {
            isSessionStopping = true
            cancelPendingReconnect()
        }

        voiceRecorder.stopRecording()

        if (!cancelPending) {
            // Run after any queued onAudioChunk callbacks to avoid dropping the tail.
            mainHandler.post {
                if (sessionAtStop != activeSessionId) return@post
                finalizeStreamingSession(sessionAtStop)
            }
        }

        updateState(State.IDLE)
    }

    private fun startStreamingSession(sessionId: Long, apiKey: String, isReconnect: Boolean = false) {
        if (sessionId != activeSessionId) return
        val editorContext = if (geminiConfig.useEditorContext) {
            try {
                priorTextProvider?.getPriorText()
            } catch (e: Exception) {
                Log.e(TAG, "Prior text provider threw: ${e.message}")
                null
            }
        } else {
            null
        }
        val sessionConfig = GeminiTranscriptionClient.buildSessionConfig(
            languageTag = getCurrentLanguageTag(),
            autoDetectLanguage = geminiConfig.autoDetectLanguage,
            transcriptionMode = geminiConfig.transcriptionMode,
            endOfSpeechSilenceMs = geminiConfig.endOfSpeechSilenceMs,
            userVocabulary = geminiConfig.customVocabulary,
            editorContext = editorContext
        )
        streamSessionId = sessionId
        isStreamingConnecting = true
        isStreamingReady = false
        streamConnectStartedAtMs = SystemClock.elapsedRealtime()
        scheduleStreamConnectTimeout(sessionId)
        if (!isReconnect) {
            finalizeWhenStreamReady = false
        }

        transcriptionClient.startStreaming(
            apiKey = apiKey,
            sessionConfig = sessionConfig,
            callback = object : GeminiTranscriptionClient.StreamingCallback {
                override fun onStreamReady() {
                    if (sessionId != activeSessionId) return
                    cancelPendingReconnect()
                    cancelStreamConnectTimeout()
                    streamReconnectAttempts = 0
                    isStreamingConnecting = false
                    isStreamingReady = true
                    acknowledgeStreamConnect(sessionId)
                    scheduleSessionRotate(
                        sessionId,
                        GeminiTranscriptionClient.SESSION_ROTATE_AFTER_MS
                    )
                    flushPendingAudio(sessionId)
                    if (finalizeWhenStreamReady) {
                        finalizeWhenStreamReady = false
                        finalizeStreamingSession(sessionId)
                    }
                }

                override fun onTranscriptionResult(segment: TranscriptSegment) {
                    if (sessionId != activeSessionId) return
                    enqueueTranscript(segment, sessionId)
                }

                override fun onServerResponse(hasTranscriptText: Boolean) {
                    if (sessionId != activeSessionId) return
                    acknowledgeGeminiResponse(sessionId, hasTranscriptText)
                }

                override fun onSessionExpiring(timeLeftMs: Long) {
                    if (sessionId != activeSessionId) return
                    scheduleSessionRotate(sessionId, timeLeftMs - SESSION_ROTATE_LEAD_MS)
                }

                override fun onStreamError(error: String) {
                    if (sessionId != activeSessionId) return
                    handleStreamDisconnected(sessionId, error)
                }

                override fun onStreamClosed() {
                    if (sessionId != activeSessionId) return
                    handleStreamDisconnected(sessionId, null)
                }
            }
        )
    }

    private fun finalizeStreamingSession(sessionId: Long) {
        if (sessionId != activeSessionId) return
        if (streamSessionId != sessionId) return

        cancelSessionRotateTimer()

        if (isStreamingReady) {
            flushPendingAudio(sessionId)
            scheduleGeminiResponseWatchdog(sessionId, "stop_finalize")
            // Sends audioStreamEnd and then keeps reading, so a phrase spoken
            // right before stop is not lost when the user taps stop without
            // waiting for the local silence window.
            transcriptionClient.finishStreaming()
            return
        }

        if (isStreamingConnecting) {
            // Stop requested before the session is ready. Finalize once onStreamReady fires.
            finalizeWhenStreamReady = true
            return
        }

        if (shouldReconnectWhileStopping() &&
            scheduleReconnect(sessionId, "finalize requested with closed stream", allowWhileStopping = true)
        ) {
            return
        }

        if (pendingAudioChunks.isNotEmpty()) {
            val message = "Final voice segment could not be transcribed completely"
            Log.e(TAG, "$message: stream was unavailable during finalize")
            listener?.onError(message)
        }
        // If the socket already died/closed, transition to idle processing state.
        pendingAudioChunks.clear()
        notifyProcessingIdleIfDrained()
    }

    /**
     * Ask Gemini to end the current turn after the local silence detector reports
     * the speaker paused, by sending `audioStreamEnd` (the documented "Hybrid
     * VAD" pattern). The session stays open and the next audio chunk reopens the
     * stream.
     *
     * The server's own end-of-speech detection is configured to be patient so
     * mid-sentence pauses do not fragment an utterance and cost accuracy. That
     * patience means a trailing phrase can sit unfinalized, so the user's
     * `PREF_VOICE_CHUNK_SILENCE_SECONDS` pause acts as the backstop that always
     * commits it.
     *
     * Fires at most once per speech-stop transition (gated by
     * [hasFinalizedCurrentSilence], re-armed on the next onSpeechStarted). The
     * chunk-silence window itself keeps calls naturally spaced, so no extra
     * global rate limit is needed — one would risk dropping a legitimate flush
     * between rapid back-to-back phrases.
     */
    private fun requestTurnFinalizeOnSilence(sessionId: Long) {
        if (sessionId != activeSessionId) return
        if (currentState != State.RECORDING) return
        if (isSessionStopping) return
        if (!isStreamingReady || streamSessionId != sessionId) return
        if (hasFinalizedCurrentSilence) return

        // Make sure any buffered audio is delivered before the finalize so the
        // tail is part of the turn Gemini closes.
        flushPendingAudio(sessionId)
        if (!isStreamingReady) return

        if (transcriptionClient.finalizeTurn()) {
            hasFinalizedCurrentSilence = true
            Log.i(TAG, "Turn finalize requested after local silence")
            scheduleGeminiResponseWatchdog(sessionId, "turn_finalize")
        }
    }

    private fun onAudioChunkCaptured(pcmData: ByteArray, sessionId: Long) {
        if (sessionId != activeSessionId) return
        if (pcmData.isEmpty()) return

        while (pendingAudioChunks.size >= MAX_PENDING_AUDIO_CHUNKS) {
            pendingAudioChunks.removeFirst()
            Log.w(
                TAG,
                "Dropped oldest buffered audio chunk " +
                    "(buffer full at $MAX_PENDING_AUDIO_CHUNKS)"
            )
            listener?.onError("Voice audio buffer overflowed before transcription completed")
        }
        // VoiceRecorder already delivers a fresh chunk copy for each callback.
        pendingAudioChunks.addLast(PendingAudioChunk(sessionId, pcmData))

        if (isStreamingReady && streamSessionId == sessionId) {
            flushPendingAudio(sessionId)
        } else if (!isStreamingConnecting && !isSessionStopping) {
            scheduleReconnectOrStop(sessionId, "audio queued while stream unavailable")
        }
    }

    private fun flushPendingAudio(sessionId: Long) {
        if (!isStreamingReady || streamSessionId != sessionId) return
        var sentAny = false
        while (true) {
            val next = pendingAudioChunks.firstOrNull() ?: break
            if (next.sessionId != sessionId) {
                pendingAudioChunks.removeFirst()
                continue
            }
            if (!transcriptionClient.sendAudioChunk(next.pcmData)) {
                // Keep remaining queue; reconnect and retry in FIFO order.
                isStreamingReady = false
                isStreamingConnecting = false
                Log.w(TAG, "Failed flushing buffered audio chunk; scheduling stream reconnect")
                scheduleReconnectOrStop(sessionId, "audio send failed")
                return
            }
            pendingAudioChunks.removeFirst()
            sentAny = true
        }
        if (
            sentAny &&
            currentState == State.RECORDING &&
            !isSessionStopping
        ) {
            scheduleGeminiResponseWatchdog(sessionId, "audio_pending")
        }
    }

    private fun enqueueTranscript(segment: TranscriptSegment, sessionId: Long) {
        if (sessionId != activeSessionId) {
            Log.i(TAG, "Dropping transcript from stale session $sessionId")
            return
        }
        val normalized = segment.text.trim()
        if (normalized.isEmpty()) return

        while (pendingTranscripts.size >= MAX_PENDING_TRANSCRIPTS) {
            val oldest = pendingTranscripts.removeFirstOrNull()
            val secondOldest = pendingTranscripts.removeFirstOrNull()
            val merged = mergeTranscriptText(
                first = oldest?.text,
                second = secondOldest?.text,
                secondAttachesToPrevious = secondOldest?.attachesToPrevious ?: false
            )
            val mergedSessionId = secondOldest?.sessionId ?: oldest?.sessionId ?: sessionId
            if (merged.isNotEmpty()) {
                pendingTranscripts.addFirst(
                    PendingTranscript(
                        sessionId = mergedSessionId,
                        text = merged,
                        attachesToPrevious = oldest?.attachesToPrevious ?: false
                    )
                )
            }
            Log.w(TAG, "Pending transcript queue full; coalesced oldest entries")
        }
        pendingTranscripts.addLast(
            PendingTranscript(
                sessionId = sessionId,
                text = normalized,
                attachesToPrevious = segment.attachesToPrevious
            )
        )
        processNextTranscript()
    }

    private fun processNextTranscript() {
        if (isDispatchingTranscripts) return
        isDispatchingTranscripts = true
        try {
            var notifiedProcessingStarted = false
            while (true) {
                val pending = pendingTranscripts.removeFirstOrNull() ?: break
                if (pending.sessionId != activeSessionId) {
                    Log.i(TAG, "Skipping transcript from stale session ${pending.sessionId}")
                    continue
                }
                if (!notifiedProcessingStarted) {
                    listener?.onProcessingStarted()
                    notifiedProcessingStarted = true
                }
                listener?.onTranscriptionResult(pending.text, pending.attachesToPrevious)
            }
        } finally {
            isDispatchingTranscripts = false
        }
        notifyProcessingIdleIfDrained()
    }

    private fun notifyProcessingIdleIfDrained() {
        if (
            !isDispatchingTranscripts &&
            pendingTranscripts.isEmpty() &&
            pendingAudioChunks.isEmpty() &&
            !isStreamingConnecting &&
            !finalizeWhenStreamReady
        ) {
            listener?.onProcessingIdle()
        }
    }

    private fun mergeTranscriptText(
        first: String?,
        second: String?,
        secondAttachesToPrevious: Boolean
    ): String {
        val left = first.orEmpty()
        val right = second.orEmpty()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val needsSpace = !secondAttachesToPrevious &&
            !left.last().isWhitespace() &&
            !right.first().isWhitespace()
        return if (needsSpace) "$left $right" else left + right
    }

    private fun handleStreamDisconnected(sessionId: Long, error: String?) {
        if (sessionId != activeSessionId) return
        cancelGeminiResponseWatchdog()
        cancelSessionRotateTimer()
        if (pendingReconnectRunnable != null && isStreamingConnecting && !isStreamingReady) {
            Log.i(TAG, "Ignoring duplicate stream disconnect callback while reconnect is already scheduled")
            return
        }
        cancelStreamConnectTimeout()
        isStreamingReady = false
        isStreamingConnecting = false

        if (currentState == State.PAUSED) {
            Log.i(TAG, "Gemini stream disconnected while paused — waiting for resume")
            notifyProcessingIdleIfDrained()
            return
        }

        if (isSessionStopping) {
            if (shouldReconnectWhileStopping() &&
                scheduleReconnect(sessionId, error ?: "stream closed while stopping", allowWhileStopping = true)
            ) {
                return
            }
            if (pendingAudioChunks.isNotEmpty()) {
                val message = "Final voice segment could not be transcribed completely"
                Log.e(TAG, "$message: ${error ?: "stream closed"}")
                listener?.onError(message)
            }
            pendingAudioChunks.clear()
            notifyProcessingIdleIfDrained()
            return
        }

        // Rejected/auth/client errors should fail fast instead of retrying the
        // same invalid session repeatedly.
        if (isUnrecoverableError(error)) {
            pendingAudioChunks.clear()
            val message = error ?: "Gemini stream rejected"
            Log.e(TAG, "Unrecoverable stream error — stopping recording: $message")
            listener?.onError(message)
            stopRecordingInternal(cancelPending = true)
            return
        }

        val sessionLikelyActive =
            currentState != State.IDLE || voiceRecorder.isCurrentlyRecording

        if (sessionLikelyActive && scheduleReconnect(sessionId, error ?: "stream closed")) {
            return
        }

        pendingAudioChunks.clear()
        val message = error ?: "Gemini stream closed"
        Log.e(TAG, "Stream disconnected unrecoverably: $message")
        listener?.onError(message)
        stopRecordingInternal(cancelPending = true)
    }

    private fun isUnrecoverableError(error: String?): Boolean {
        if (error == null) return false
        val lower = error.lowercase()
        return (lower.contains("invalid") && lower.contains("api key")) ||
            lower.contains("not allowed to use") ||
            lower.contains("missing api key") ||
            lower.contains("unauthenticated") ||
            lower.contains("unauthorized") ||
            lower.contains("permission_denied") ||
            lower.contains("authentication failed") ||
            lower.contains("connection rejected") ||
            lower.contains("is not available for this key") ||
            lower.contains("rejected the request") ||
            lower.contains("rejected this api key") ||
            lower.contains("rejected the transcription session setup") ||
            lower.contains("requires billing") ||
            lower.contains("rate limited") ||
            lower.contains("too many requests")
    }

    private fun scheduleReconnectOrStop(sessionId: Long, reason: String) {
        if (sessionId != activeSessionId) return
        val allowWhileStopping = shouldReconnectWhileStopping()
        if (isSessionStopping && !allowWhileStopping) return
        if (scheduleReconnect(sessionId, reason, allowWhileStopping = allowWhileStopping)) return

        pendingAudioChunks.clear()
        val message = if (allowWhileStopping) {
            "Final voice segment could not be transcribed completely"
        } else {
            "Gemini stream unavailable: $reason"
        }
        Log.e(TAG, message)
        listener?.onError(message)
        stopRecordingInternal(cancelPending = true)
    }

    private fun scheduleReconnect(
        sessionId: Long,
        reason: String,
        allowWhileStopping: Boolean = false
    ): Boolean {
        if (sessionId != activeSessionId) return false
        if (isSessionStopping && !allowWhileStopping) return false
        if (streamReconnectAttempts >= MAX_STREAM_RECONNECT_ATTEMPTS) {
            Log.e(
                TAG,
                "Reconnect attempts exhausted ($MAX_STREAM_RECONNECT_ATTEMPTS), reason=$reason"
            )
            return false
        }
        if (sessionApiKey.isBlank()) {
            Log.e(TAG, "Cannot reconnect stream: missing Gemini API key")
            return false
        }
        cancelPendingReconnect()
        streamReconnectAttempts += 1
        val delayMs = STREAM_RECONNECT_BASE_DELAY_MS * (1L shl (streamReconnectAttempts - 1))
        isStreamingConnecting = true
        Log.w(
            TAG,
            "Scheduling Gemini reconnect in ${delayMs}ms " +
                "(attempt $streamReconnectAttempts/$MAX_STREAM_RECONNECT_ATTEMPTS, reason=$reason)"
        )
        val reconnectRunnable = Runnable {
            pendingReconnectRunnable = null
            if (sessionId != activeSessionId) {
                return@Runnable
            }
            if (isSessionStopping && !allowWhileStopping) {
                return@Runnable
            }
            val sessionStillActive =
                currentState != State.IDLE ||
                    voiceRecorder.isCurrentlyRecording ||
                    (allowWhileStopping && shouldReconnectWhileStopping())
            if (!sessionStillActive) {
                Log.i(TAG, "Skipping reconnect: voice session no longer active")
                isStreamingConnecting = false
                return@Runnable
            }
            startStreamingSession(sessionId, sessionApiKey, isReconnect = true)
        }
        pendingReconnectRunnable = reconnectRunnable
        mainHandler.postDelayed(reconnectRunnable, delayMs)
        return true
    }

    private fun shouldReconnectWhileStopping(): Boolean {
        return isSessionStopping && (pendingAudioChunks.isNotEmpty() || finalizeWhenStreamReady)
    }

    private fun cancelPendingReconnect() {
        val runnable = pendingReconnectRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        pendingReconnectRunnable = null
    }

    private fun scheduleStreamConnectTimeout(sessionId: Long) {
        cancelStreamConnectTimeout()
        val timeoutRunnable = Runnable {
            pendingStreamConnectTimeoutRunnable = null
            if (sessionId != activeSessionId) return@Runnable
            if (!isStreamingConnecting || isStreamingReady || streamSessionId != sessionId) return@Runnable
            val message = "Gemini stream connection timed out"
            Log.e(
                TAG,
                "VOICE_RESPONSE timeout after ${STREAM_CONNECT_TIMEOUT_MS}ms (stream_connect): $message"
            )
            // Ensure the stale socket lifecycle is torn down before reconnection handling.
            transcriptionClient.cancelAll()
            handleStreamDisconnected(sessionId, message)
        }
        pendingStreamConnectTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, STREAM_CONNECT_TIMEOUT_MS)
    }

    private fun cancelStreamConnectTimeout() {
        val runnable = pendingStreamConnectTimeoutRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        pendingStreamConnectTimeoutRunnable = null
    }

    /**
     * Move the dictation onto a fresh connection before the current one is
     * terminated. Live transcription sessions are capped at 10 minutes, and the
     * server announces the impending disconnect with `goAway`. Rotating is not an
     * error, so it does not consume a reconnect attempt; buffered audio carries
     * across the gap.
     */
    private fun scheduleSessionRotate(sessionId: Long, delayMs: Long) {
        if (sessionId != activeSessionId) return
        cancelSessionRotateTimer()
        val safeDelayMs = delayMs.coerceAtLeast(0L)
        val rotateRunnable = Runnable {
            pendingSessionRotateRunnable = null
            if (sessionId != activeSessionId) return@Runnable
            if (isSessionStopping || currentState == State.IDLE) return@Runnable
            if (sessionApiKey.isBlank()) return@Runnable
            Log.i(TAG, "Rotating Gemini session onto a fresh connection")
            streamReconnectAttempts = 0
            startStreamingSession(sessionId, sessionApiKey, isReconnect = true)
        }
        pendingSessionRotateRunnable = rotateRunnable
        mainHandler.postDelayed(rotateRunnable, safeDelayMs)
    }

    private fun cancelSessionRotateTimer() {
        val runnable = pendingSessionRotateRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        pendingSessionRotateRunnable = null
    }

    private fun reloadRuntimeConfig() {
        val prefs = context.prefs()

        val chunkSilenceSeconds = prefs.getInt(
            Settings.PREF_VOICE_CHUNK_SILENCE_SECONDS,
            Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS
        ).coerceIn(MIN_CHUNK_SILENCE_SECONDS, MAX_CHUNK_SILENCE_SECONDS)

        val autoStopSilenceSeconds = prefs.getInt(
            Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
            Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS
        ).coerceIn(
            MIN_AUTO_STOP_SILENCE_SECONDS,
            MAX_AUTO_STOP_SILENCE_SECONDS
        )

        val silenceThreshold = prefs.getInt(
            Settings.PREF_VOICE_SILENCE_THRESHOLD,
            Defaults.PREF_VOICE_SILENCE_THRESHOLD
        ).coerceIn(MIN_SILENCE_THRESHOLD, MAX_SILENCE_THRESHOLD)

        chunkSilenceDurationMs = chunkSilenceSeconds * 1000L
        autoStopSilenceMs = autoStopSilenceSeconds * 1000L
        chunkSilenceThreshold = silenceThreshold.toDouble()
        geminiConfig = TranscriptionPreferences.readGeminiConfig(prefs)

        voiceRecorder.updateSilenceConfig(
            silenceDurationMs = chunkSilenceDurationMs,
            silenceThreshold = chunkSilenceThreshold
        )

        Log.i(
            TAG,
            "Voice config loaded: localSpeechSilence=${chunkSilenceDurationMs}ms, " +
                "silenceThreshold=${chunkSilenceThreshold}, " +
                "autoStopSilence=${autoStopSilenceMs}ms, " +
                "geminiMode=${geminiConfig.transcriptionMode}, " +
                "geminiEndOfSpeechSilenceMs=${geminiConfig.endOfSpeechSilenceMs}, " +
                "geminiAutoDetectLanguage=${geminiConfig.autoDetectLanguage}, " +
                "geminiUseEditorContext=${geminiConfig.useEditorContext}, " +
                "geminiCustomVocabulary=${geminiConfig.customVocabulary.size}"
        )
    }

    private fun updateState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            listener?.onStateChanged(newState)
        }
    }

    // ── Timers ─────────────────────────────────────────────────────────

    private fun startAutoStopTimer() {
        mainHandler.removeCallbacks(autoStopSilenceRunnable)
        if (currentState == State.RECORDING) {
            Log.i(TAG, "Starting auto-stop timer: ${autoStopSilenceMs}ms")
            mainHandler.postDelayed(autoStopSilenceRunnable, autoStopSilenceMs)
        }
    }

    private fun cancelAutoStopTimer() {
        mainHandler.removeCallbacks(autoStopSilenceRunnable)
    }

    // ── Gemini response latency watchdog ───────────────────────────────

    private fun scheduleGeminiResponseWatchdog(
        sessionId: Long,
        reason: String,
        timeoutMs: Long = GEMINI_RESPONSE_TIMEOUT_MS
    ) {
        cancelGeminiResponseWatchdog()
        val startedAtMs = SystemClock.elapsedRealtime()
        awaitingGeminiResponse = AwaitingGeminiResponse(
            sessionId = sessionId,
            reason = reason,
            startedAtMs = startedAtMs,
            timeoutMs = timeoutMs
        )
        val runnable = Runnable {
            geminiResponseTimeoutRunnable = null
            val pending = awaitingGeminiResponse ?: return@Runnable
            if (pending.sessionId != activeSessionId) return@Runnable
            val elapsed = SystemClock.elapsedRealtime() - pending.startedAtMs
            awaitingGeminiResponse = null
            Log.e(
                TAG,
                "VOICE_RESPONSE timeout after ${elapsed}ms (${pending.reason}): " +
                    "no Gemini response; streamReady=$isStreamingReady, " +
                    "pendingAudio=${pendingAudioChunks.size}, " +
                    "reconnectAttempt=$streamReconnectAttempts"
            )
        }
        geminiResponseTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, timeoutMs)
    }

    private fun acknowledgeStreamConnect(sessionId: Long) {
        if (sessionId != activeSessionId || streamConnectStartedAtMs <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - streamConnectStartedAtMs
        streamConnectStartedAtMs = 0L
        Log.i(TAG, "VOICE_RESPONSE ok in ${elapsed}ms (stream_connect): stream ready")
    }

    private fun acknowledgeGeminiResponse(sessionId: Long, hasTranscriptText: Boolean) {
        val pending = awaitingGeminiResponse ?: return
        if (pending.sessionId != sessionId) return
        cancelGeminiResponseWatchdog()
        val elapsed = SystemClock.elapsedRealtime() - pending.startedAtMs
        val detail = if (hasTranscriptText) "transcript received" else "interim or turn boundary"
        Log.i(TAG, "VOICE_RESPONSE ok in ${elapsed}ms (${pending.reason}): $detail")
    }

    private fun cancelGeminiResponseWatchdog() {
        geminiResponseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        geminiResponseTimeoutRunnable = null
        awaitingGeminiResponse = null
    }

    // ── Settings ───────────────────────────────────────────────────────

    private fun getApiKey(): String {
        return try {
            TranscriptionPreferences.readGeminiApiKey(context.prefs())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            ""
        }
    }

    /**
     * Full language tag of the active keyboard subtype (for example `en_US`), so
     * the client can pick the matching BCP-47 regional variant Gemini expects
     * rather than a bare language code.
     */
    private fun getCurrentLanguageTag(): String? {
        return try {
            val locale = Settings.getValues()?.mLocale ?: return null
            if (locale.language.isBlank() || locale.language == "und") return null
            locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting language: ${e.message}")
            null
        }
    }
}
