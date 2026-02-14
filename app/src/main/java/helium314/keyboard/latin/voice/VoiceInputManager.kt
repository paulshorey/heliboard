// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Manages the voice input workflow:
 *
 * 1. Record audio locally via [VoiceRecorder] (starts instantly).
 * 2. Stream raw PCM chunks to Deepgram over WebSocket.
 * 3. Receive finalized transcript updates from Deepgram in stream order.
 * 4. Deliver transcript text to [VoiceInputListener.onTranscriptionResult].
 *
 * Cleanup ordering / context replacement remains the responsibility of the IME
 * listener so the same deterministic find-and-replace behavior is preserved.
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"

        private const val MIN_CHUNK_SILENCE_SECONDS = 1
        private const val MAX_CHUNK_SILENCE_SECONDS = 30
        private const val MIN_NEW_PARAGRAPH_SILENCE_SECONDS = 3
        private const val MAX_NEW_PARAGRAPH_SILENCE_SECONDS = 120
        private const val MIN_SILENCE_THRESHOLD = 40
        private const val MAX_SILENCE_THRESHOLD = 5000

        /**
         * Auto-stop recording after this many milliseconds of continuous silence
         * (no speech detected). Prevents the recording from running indefinitely
         * when the user walks away or stops talking.
         */
        private const val AUTO_STOP_SILENCE_MS = 30_000L

        /** Maximum buffered raw PCM chunks while waiting for socket readiness. */
        private const val MAX_PENDING_AUDIO_CHUNKS = 300

        /** Maximum finalized transcripts waiting to be forwarded to the listener. */
        private const val MAX_PENDING_TRANSCRIPTS = 64
    }

    enum class State {
        IDLE,       // Not doing anything
        RECORDING,  // Actively recording (microphone is live)
        PAUSED      // Recording paused by user
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)

        /** A transcript unit was finalized — process and insert this text. */
        fun onTranscriptionResult(text: String)

        /** Voice processing is actively running (transcription/cleanup pending). */
        fun onProcessingStarted()

        /** No queued transcription work remains at manager level. */
        fun onProcessingIdle()

        /** Configured silence window elapsed — start a new paragraph. */
        fun onNewParagraphRequested()

        fun onError(error: String)
        fun onPermissionRequired()
    }

    private data class PendingAudioChunk(
        val sessionId: Long,
        val pcmData: ByteArray
    )

    private data class PendingTranscript(
        val sessionId: Long,
        val text: String
    )

    private val voiceRecorder = VoiceRecorder(context)
    private val transcriptionClient = DeepgramTranscriptionClient()
    private var listener: VoiceInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentState = State.IDLE
    private var activeSessionId = 0L

    // Local speech-boundary detection window used by VoiceRecorder callbacks
    // (paragraph/auto-stop behavior). Deepgram chunking/finalization is server-managed.
    private var chunkSilenceDurationMs = Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS * 1000L
    private var chunkSilenceThreshold = Defaults.PREF_VOICE_SILENCE_THRESHOLD.toDouble()
    private var newParagraphDelayMs = Defaults.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS * 1000L

    // Streaming state
    private var streamSessionId = 0L
    private var isStreamingReady = false
    private var isStreamingConnecting = false
    private var finalizeWhenStreamReady = false

    // Buffered audio while stream is not yet open
    private val pendingAudioChunks = ArrayDeque<PendingAudioChunk>()

    // Finalized transcript delivery queue (strict FIFO)
    private val pendingTranscripts = ArrayDeque<PendingTranscript>()
    private var isDispatchingTranscripts = false

    // New paragraph timer — insert paragraph break after long silence
    private val newParagraphTimerRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(
                TAG,
                "New paragraph timer fired after ${newParagraphDelayMs}ms " +
                    "(paragraph break only; recording continues)"
            )
            listener?.onNewParagraphRequested()
        }
    }

    // Auto-stop timer — stop recording after prolonged silence (no speech)
    private val autoStopSilenceRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(
                TAG,
                "Auto-stop timer fired after ${AUTO_STOP_SILENCE_MS}ms of silence — stopping recording"
            )
            stopRecording()
        }
    }

    val isRecording: Boolean get() = currentState == State.RECORDING
    val isPaused: Boolean get() = currentState == State.PAUSED
    val isIdle: Boolean get() = currentState == State.IDLE
    val state: State get() = currentState

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
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
     * Start recording. Microphone starts immediately; Deepgram stream connects in parallel.
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
            listener?.onError("Deepgram API key not configured. Please set it in Settings.")
            return false
        }

        reloadRuntimeConfig()
        beginNewSession()
        val sessionId = activeSessionId

        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                if (sessionId != activeSessionId) return
                updateState(State.RECORDING)
                // Start the auto-stop timer immediately so recording eventually
                // stops even when the user never speaks.
                startAutoStopTimer()
                Log.i(TAG, "VOICE_STEP_1 recording callback received")
            }

            override fun onAudioChunk(pcmData: ByteArray) {
                if (sessionId != activeSessionId) return
                onAudioChunkCaptured(pcmData, sessionId)
            }

            override fun onSegmentReady(wavData: ByteArray) {
                // Local WAV segmentation is no longer used for transcription.
            }

            override fun onSpeechStarted() {
                if (sessionId != activeSessionId) return
                cancelNewParagraphTimer()
                cancelAutoStopTimer()
            }

            override fun onSpeechStopped() {
                if (sessionId != activeSessionId) return
                startNewParagraphTimer()
                startAutoStopTimer()
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

        return true
    }

    fun stopRecording() {
        if (currentState == State.IDLE) return
        Log.i(TAG, "Stopping recording")
        stopRecordingInternal(cancelPending = false)
    }

    fun cancelRecording() {
        Log.i(TAG, "Cancelling recording")
        stopRecordingInternal(cancelPending = true)
    }

    fun pauseRecording() {
        if (currentState != State.RECORDING) return
        cancelNewParagraphTimer()
        cancelAutoStopTimer()
        voiceRecorder.pauseRecording()
        updateState(State.PAUSED)
    }

    fun resumeRecording() {
        if (currentState != State.PAUSED) return
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
        activeSessionId += 1
        streamSessionId = 0L
        isStreamingReady = false
        isStreamingConnecting = false
        finalizeWhenStreamReady = false
        pendingAudioChunks.clear()
        pendingTranscripts.clear()
        isDispatchingTranscripts = false
        transcriptionClient.cancelAll()
        notifyProcessingIdleIfDrained()
        Log.i(TAG, "Voice session invalidated ($reason), sessionId=$activeSessionId")
    }

    private fun stopRecordingInternal(cancelPending: Boolean) {
        cancelNewParagraphTimer()
        cancelAutoStopTimer()

        val sessionAtStop = activeSessionId
        if (cancelPending) {
            invalidateActiveSession("recording cancelled")
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

    private fun startStreamingSession(sessionId: Long, apiKey: String) {
        if (sessionId != activeSessionId) return
        val language = getCurrentLanguage()
        streamSessionId = sessionId
        isStreamingConnecting = true
        isStreamingReady = false
        finalizeWhenStreamReady = false

        transcriptionClient.startStreaming(
            apiKey = apiKey,
            language = language,
            callback = object : DeepgramTranscriptionClient.StreamingCallback {
                override fun onStreamReady() {
                    if (sessionId != activeSessionId) return
                    isStreamingConnecting = false
                    isStreamingReady = true
                    flushPendingAudio(sessionId)
                    if (finalizeWhenStreamReady) {
                        finalizeWhenStreamReady = false
                        finalizeStreamingSession(sessionId)
                    }
                }

                override fun onTranscriptionResult(text: String) {
                    if (sessionId != activeSessionId) return
                    enqueueTranscript(text, sessionId)
                }

                override fun onStreamError(error: String) {
                    if (sessionId != activeSessionId) return
                    isStreamingConnecting = false
                    isStreamingReady = false
                    pendingAudioChunks.clear()
                    Log.e(TAG, "Deepgram streaming error: $error")
                    listener?.onError(error)

                    // If streaming died mid-session, stop recording to avoid buffering
                    // unbounded audio that can no longer be transcribed.
                    if (currentState != State.IDLE) {
                        stopRecordingInternal(cancelPending = false)
                    } else {
                        notifyProcessingIdleIfDrained()
                    }
                }

                override fun onStreamClosed() {
                    if (sessionId != activeSessionId) return
                    isStreamingConnecting = false
                    isStreamingReady = false
                    pendingAudioChunks.clear()
                    notifyProcessingIdleIfDrained()
                }
            }
        )
    }

    private fun finalizeStreamingSession(sessionId: Long) {
        if (sessionId != activeSessionId) return
        if (streamSessionId != sessionId) return

        if (isStreamingReady) {
            flushPendingAudio(sessionId)
            transcriptionClient.finishStreaming()
            return
        }

        if (isStreamingConnecting) {
            // Stop requested before socket is ready. Finalize once onStreamReady fires.
            finalizeWhenStreamReady = true
            return
        }

        // If the socket already died/closed, just transition to idle processing state.
        notifyProcessingIdleIfDrained()
    }

    private fun onAudioChunkCaptured(pcmData: ByteArray, sessionId: Long) {
        if (sessionId != activeSessionId) return
        if (pcmData.isEmpty()) return

        if (isStreamingReady && streamSessionId == sessionId) {
            val sent = transcriptionClient.sendAudioChunk(pcmData)
            if (sent) {
                return
            }
            Log.w(TAG, "Streaming send rejected chunk; buffering temporarily")
        }

        while (pendingAudioChunks.size >= MAX_PENDING_AUDIO_CHUNKS) {
            pendingAudioChunks.removeFirst()
            Log.w(
                TAG,
                "Dropped oldest buffered audio chunk " +
                    "(buffer full at $MAX_PENDING_AUDIO_CHUNKS)"
            )
        }
        pendingAudioChunks.addLast(PendingAudioChunk(sessionId, pcmData.copyOf()))
    }

    private fun flushPendingAudio(sessionId: Long) {
        if (!isStreamingReady || streamSessionId != sessionId) return
        while (true) {
            val next = pendingAudioChunks.firstOrNull() ?: break
            if (next.sessionId != sessionId) {
                pendingAudioChunks.removeFirst()
                continue
            }
            if (!transcriptionClient.sendAudioChunk(next.pcmData)) {
                // Keep the remaining queue; we'll retry when the socket recovers or reconnects.
                Log.w(TAG, "Failed flushing buffered audio chunk; keeping backlog")
                return
            }
            pendingAudioChunks.removeFirst()
        }
    }

    private fun enqueueTranscript(text: String, sessionId: Long) {
        if (sessionId != activeSessionId) {
            Log.i(TAG, "Dropping transcript from stale session $sessionId")
            return
        }
        val normalized = text.trim()
        if (normalized.isEmpty()) return

        while (pendingTranscripts.size >= MAX_PENDING_TRANSCRIPTS) {
            pendingTranscripts.removeFirst()
            Log.w(
                TAG,
                "Dropped oldest pending transcript " +
                    "(queue full at $MAX_PENDING_TRANSCRIPTS)"
            )
        }
        pendingTranscripts.addLast(PendingTranscript(sessionId, normalized))
        processNextTranscript()
    }

    private fun processNextTranscript() {
        if (isDispatchingTranscripts) return
        isDispatchingTranscripts = true
        try {
            while (true) {
                val pending = pendingTranscripts.removeFirstOrNull() ?: break
                if (pending.sessionId != activeSessionId) {
                    Log.i(TAG, "Skipping transcript from stale session ${pending.sessionId}")
                    continue
                }
                listener?.onProcessingStarted()
                listener?.onTranscriptionResult(pending.text)
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
            pendingAudioChunks.isEmpty()
        ) {
            listener?.onProcessingIdle()
        }
    }

    private fun reloadRuntimeConfig() {
        val prefs = context.prefs()

        val chunkSilenceSeconds = prefs.getInt(
            Settings.PREF_VOICE_CHUNK_SILENCE_SECONDS,
            Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS
        ).coerceIn(MIN_CHUNK_SILENCE_SECONDS, MAX_CHUNK_SILENCE_SECONDS)

        val paragraphSilenceSeconds = prefs.getInt(
            Settings.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS,
            Defaults.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS
        ).coerceIn(
            MIN_NEW_PARAGRAPH_SILENCE_SECONDS,
            MAX_NEW_PARAGRAPH_SILENCE_SECONDS
        )

        val silenceThreshold = prefs.getInt(
            Settings.PREF_VOICE_SILENCE_THRESHOLD,
            Defaults.PREF_VOICE_SILENCE_THRESHOLD
        ).coerceIn(MIN_SILENCE_THRESHOLD, MAX_SILENCE_THRESHOLD)

        chunkSilenceDurationMs = chunkSilenceSeconds * 1000L
        newParagraphDelayMs = paragraphSilenceSeconds * 1000L
        chunkSilenceThreshold = silenceThreshold.toDouble()

        voiceRecorder.updateSilenceConfig(
            silenceDurationMs = chunkSilenceDurationMs,
            silenceThreshold = chunkSilenceThreshold
        )

        Log.i(
            TAG,
            "Voice config loaded: localSpeechSilence=${chunkSilenceDurationMs}ms, " +
                "silenceThreshold=${chunkSilenceThreshold}, " +
                "newParagraphSilence=${newParagraphDelayMs}ms"
        )
    }

    private fun updateState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            listener?.onStateChanged(newState)
        }
    }

    // ── Timers ─────────────────────────────────────────────────────────

    private fun startNewParagraphTimer() {
        mainHandler.removeCallbacks(newParagraphTimerRunnable)
        if (currentState == State.RECORDING) {
            Log.i(TAG, "Starting new paragraph timer: ${newParagraphDelayMs}ms")
            mainHandler.postDelayed(newParagraphTimerRunnable, newParagraphDelayMs)
        }
    }

    private fun cancelNewParagraphTimer() {
        mainHandler.removeCallbacks(newParagraphTimerRunnable)
    }

    private fun startAutoStopTimer() {
        mainHandler.removeCallbacks(autoStopSilenceRunnable)
        if (currentState == State.RECORDING) {
            Log.i(TAG, "Starting auto-stop timer: ${AUTO_STOP_SILENCE_MS}ms")
            mainHandler.postDelayed(autoStopSilenceRunnable, AUTO_STOP_SILENCE_MS)
        }
    }

    private fun cancelAutoStopTimer() {
        mainHandler.removeCallbacks(autoStopSilenceRunnable)
    }

    // ── Settings ───────────────────────────────────────────────────────

    private fun getApiKey(): String {
        return try {
            context.prefs().getString(Settings.PREF_DEEPGRAM_API_KEY, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            ""
        }
    }

    private fun getCurrentLanguage(): String? {
        return try {
            Settings.getValues()?.mLocale?.language
        } catch (e: Exception) {
            Log.e(TAG, "Error getting language: ${e.message}")
            null
        }
    }
}
