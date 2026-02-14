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
 * 1. Records audio locally via [VoiceRecorder] (starts instantly).
 * 2. When silence is detected, [VoiceRecorder] emits a WAV segment.
 * 3. Segment is sent to Deepgram for transcription.
 * 4. Transcription result is delivered to [VoiceInputListener.onTranscriptionResult].
 *    The listener is responsible for cleanup-before-insert ordering.
 *
 * State machine: IDLE → RECORDING ↔ PAUSED → IDLE
 * (No CONNECTING state — recording starts instantly with no network dependency.)
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"

        /**
         * Fallback watchdog: if we stay in RECORDING too long without a detected
         * chunk boundary, ask [VoiceRecorder] to flush the current segment.
         */
        private const val MIN_CHUNK_WATCHDOG_MS = 8_000L
        private const val CHUNK_WATCHDOG_EXTRA_MS = 4_000L

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
        private const val AUTO_STOP_SILENCE_MS = 36_000L

        /** Maximum segments waiting in the transcription queue.
         *  Prevents backlog when noisy environments produce segments faster
         *  than Deepgram can process them. Oldest segments are dropped. */
        private const val MAX_PENDING_SEGMENTS = 3
    }

    enum class State {
        IDLE,       // Not doing anything
        RECORDING,  // Actively recording (microphone is live)
        PAUSED      // Recording paused by user
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)

        /** A segment was transcribed — insert this text. */
        fun onTranscriptionResult(text: String)

        /** An audio chunk is being sent for transcription/processing. */
        fun onProcessingStarted()

        /** Configured silence window elapsed — start a new paragraph. */
        fun onNewParagraphRequested()

        fun onError(error: String)
        fun onPermissionRequired()
    }

    private data class PendingSegment(
        val sessionId: Long,
        val wavData: ByteArray
    )

    private val voiceRecorder = VoiceRecorder(context)
    private val transcriptionClient = DeepgramTranscriptionClient()
    private var listener: VoiceInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentState = State.IDLE
    private var activeSessionId = 0L

    private var chunkSilenceDurationMs = Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS * 1000L
    private var chunkSilenceThreshold = Defaults.PREF_VOICE_SILENCE_THRESHOLD.toDouble()
    private var newParagraphDelayMs = Defaults.PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS * 1000L
    private var chunkWatchdogMs = MIN_CHUNK_WATCHDOG_MS

    // Transcription pipeline: queue chunks and process strictly in order.
    private val pendingSegments = ArrayDeque<PendingSegment>()
    private var isTranscribingSegment = false
    private var inFlightRequestToken = 0L
    private var nextRequestToken = 0L

    // Chunk watchdog — force a flush if silence detection misses a split.
    private val chunkWatchdogRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.w(
                TAG,
                "Chunk watchdog fired after ${chunkWatchdogMs}ms — forcing segment flush"
            )
            voiceRecorder.requestSegmentFlush()
            // Keep fallback active while recording in case a single flush
            // does not produce a clean boundary.
            resetChunkWatchdog()
        }
    }

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
     * Start recording. The microphone begins immediately — no network call.
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

        // Wire up the recorder callback
        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                if (sessionId != activeSessionId) return
                updateState(State.RECORDING)
                resetChunkWatchdog()
                // Start the auto-stop timer immediately so that recording stops
                // after prolonged silence even if the user never speaks.
                startAutoStopTimer()
                Log.i(TAG, "VOICE_STEP_1 recording callback received")
            }

            override fun onSegmentReady(wavData: ByteArray) {
                if (sessionId != activeSessionId) return
                enqueueSegment(wavData, sessionId)
            }

            override fun onSpeechStarted() {
                if (sessionId != activeSessionId) return
                cancelNewParagraphTimer()
                cancelAutoStopTimer()
                resetChunkWatchdog()
            }

            override fun onSpeechStopped() {
                if (sessionId != activeSessionId) return
                cancelChunkWatchdog()
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
        cancelChunkWatchdog()
        cancelNewParagraphTimer()
        cancelAutoStopTimer()
        voiceRecorder.pauseRecording()
        updateState(State.PAUSED)
    }

    fun resumeRecording() {
        if (currentState != State.PAUSED) return
        voiceRecorder.resumeRecording()
        updateState(State.RECORDING)
        resetChunkWatchdog()
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
        pendingSegments.clear()
        isTranscribingSegment = false
        inFlightRequestToken = 0L
        transcriptionClient.cancelAll()
        Log.i(TAG, "Voice session invalidated ($reason), sessionId=$activeSessionId")
    }

    private fun stopRecordingInternal(cancelPending: Boolean) {
        cancelChunkWatchdog()
        cancelNewParagraphTimer()
        cancelAutoStopTimer()
        if (cancelPending) {
            invalidateActiveSession("recording cancelled")
        }
        voiceRecorder.stopRecording()
        updateState(State.IDLE)
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
        chunkWatchdogMs = maxOf(
            MIN_CHUNK_WATCHDOG_MS,
            chunkSilenceDurationMs + CHUNK_WATCHDOG_EXTRA_MS
        )

        voiceRecorder.updateSilenceConfig(
            silenceDurationMs = chunkSilenceDurationMs,
            silenceThreshold = chunkSilenceThreshold
        )

        Log.i(
            TAG,
            "Voice config loaded: chunkSilence=${chunkSilenceDurationMs}ms, " +
                "silenceThreshold=${chunkSilenceThreshold}, " +
                "newParagraphSilence=${newParagraphDelayMs}ms, " +
                "watchdog=${chunkWatchdogMs}ms"
        )
    }

    private fun updateState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            listener?.onStateChanged(newState)
        }
    }

    /**
     * Queue a segment for transcription in strict FIFO order.
     */
    private fun enqueueSegment(wavData: ByteArray, sessionId: Long) {
        if (sessionId != activeSessionId) {
            Log.i(TAG, "Dropping segment from stale session $sessionId")
            return
        }
        if (wavData.isEmpty()) return
        // Limit queue to avoid backlog when segments arrive faster than processing
        while (pendingSegments.size >= MAX_PENDING_SEGMENTS) {
            pendingSegments.removeFirst()
            Log.w(TAG, "Dropped oldest queued segment (queue full at $MAX_PENDING_SEGMENTS)")
        }
        pendingSegments.addLast(PendingSegment(sessionId, wavData))
        processNextSegment()
    }

    /**
     * Process queued segments sequentially to keep transcript order deterministic
     * and avoid overlapping requests/race conditions.
     */
    private fun processNextSegment() {
        if (isTranscribingSegment) return

        val segment = pendingSegments.removeFirstOrNull() ?: return
        if (segment.sessionId != activeSessionId) {
            Log.i(TAG, "Skipping queued segment from stale session ${segment.sessionId}")
            processNextSegment()
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            listener?.onError("Deepgram API key not configured")
            processNextSegment()
            return
        }

        val requestToken = ++nextRequestToken
        inFlightRequestToken = requestToken
        isTranscribingSegment = true

        val language = getCurrentLanguage()
        Log.i(
            TAG,
            "VOICE_STEP_3 sending chunk to Deepgram: bytes=${segment.wavData.size}, " +
                "queuedAfterSend=${pendingSegments.size}, session=${segment.sessionId}"
        )

        // Notify the listener that processing has started so the UI can
        // show a "..." indicator while transcription + cleanup run.
        listener?.onProcessingStarted()

        transcriptionClient.transcribe(
            apiKey = apiKey,
            wavData = segment.wavData,
            language = language,
            callback = object : DeepgramTranscriptionClient.TranscriptionCallback {
                override fun onTranscriptionComplete(text: String) {
                    completeTranscriptionRequest(requestToken, segment.sessionId) {
                        if (text.isNotBlank()) {
                            Log.i(
                                TAG,
                                "VOICE_STEP_4 transcription received (${text.length} chars) — applying post-processing"
                            )
                        } else {
                            Log.i(
                                TAG,
                                "VOICE_STEP_4 transcription returned empty text for segment"
                            )
                        }
                        // Forward all results (including empty) so the IME can
                        // reliably clear processing state for no-speech chunks.
                        listener?.onTranscriptionResult(text)
                    }
                }

                override fun onTranscriptionError(error: String) {
                    completeTranscriptionRequest(requestToken, segment.sessionId) {
                        Log.e(TAG, "Transcription error: $error")
                        listener?.onError(error)
                    }
                }
            }
        )
    }

    private fun completeTranscriptionRequest(
        requestToken: Long,
        sessionId: Long,
        onCurrentSession: () -> Unit
    ) {
        if (inFlightRequestToken != requestToken) {
            // Session was invalidated and this callback belongs to an old request.
            Log.i(TAG, "Ignoring stale transcription callback token=$requestToken")
            return
        }

        inFlightRequestToken = 0L
        isTranscribingSegment = false

        if (sessionId == activeSessionId) {
            onCurrentSession()
        } else {
            Log.i(TAG, "Ignoring transcription callback from stale session $sessionId")
        }

        processNextSegment()
    }

    // ── Timers ─────────────────────────────────────────────────────────

    private fun resetChunkWatchdog() {
        mainHandler.removeCallbacks(chunkWatchdogRunnable)
        if (currentState == State.RECORDING) {
            mainHandler.postDelayed(chunkWatchdogRunnable, chunkWatchdogMs)
        }
    }

    private fun cancelChunkWatchdog() {
        mainHandler.removeCallbacks(chunkWatchdogRunnable)
    }

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
