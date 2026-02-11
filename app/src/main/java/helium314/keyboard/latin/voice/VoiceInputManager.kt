// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * 5. After transcription is inserted, cleanup is requested so the caller
 *    can send the current paragraph to Anthropic for post-processing.
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
        private const val CHUNK_WATCHDOG_MS = 20_000L

        /** After transcription is inserted, wait this long then request cleanup. */
        private const val CLEANUP_DELAY_MS = 3000L

        /** After prolonged silence, request a new paragraph. */
        private const val NEW_PARAGRAPH_DELAY_MS = 12_000L
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

        /** 3 seconds after last transcription — time to clean up the paragraph. */
        fun onCleanupRequested()

        /** 12 seconds of silence — start a new paragraph. */
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

    // Transcription pipeline: queue chunks and process strictly in order.
    private val pendingSegments = ArrayDeque<PendingSegment>()
    private var isTranscribingSegment = false
    private var inFlightRequestToken = 0L
    private var nextRequestToken = 0L

    // Chunk watchdog — force a flush if silence detection misses a split.
    private val chunkWatchdogRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.w(TAG, "Chunk watchdog fired — forcing segment flush")
            voiceRecorder.requestSegmentFlush()
            resetChunkWatchdog()
        }
    }

    // Cleanup timer — trigger cleanup after silence following transcription
    private val cleanupTimerRunnable = Runnable {
        if (currentState == State.RECORDING || currentState == State.IDLE) {
            Log.i(TAG, "Cleanup timer fired")
            listener?.onCleanupRequested()
        }
    }

    // New paragraph timer — insert paragraph break after long silence
    private val newParagraphTimerRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(TAG, "New paragraph timer fired")
            listener?.onNewParagraphRequested()
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
        if (!voiceRecorder.hasRecordPermission()) {
            listener?.onPermissionRequired()
            return false
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            listener?.onError("Deepgram API key not configured. Please set it in Settings.")
            return false
        }

        beginNewSession()
        val sessionId = activeSessionId

        // Wire up the recorder callback
        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                if (sessionId != activeSessionId) return
                updateState(State.RECORDING)
                resetChunkWatchdog()
            }

            override fun onSegmentReady(wavData: ByteArray) {
                if (sessionId != activeSessionId) return
                enqueueSegment(wavData, sessionId)
                resetChunkWatchdog()
            }

            override fun onSpeechStarted() {
                if (sessionId != activeSessionId) return
                cancelCleanupTimer()
                cancelNewParagraphTimer()
                resetChunkWatchdog()
            }

            override fun onSpeechStopped() {
                if (sessionId != activeSessionId) return
                startNewParagraphTimer()
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
        cancelCleanupTimer()
        cancelNewParagraphTimer()
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
        cancelCleanupTimer()
        cancelNewParagraphTimer()
        if (cancelPending) {
            invalidateActiveSession("recording cancelled")
        }
        voiceRecorder.stopRecording()
        updateState(State.IDLE)
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

        transcriptionClient.transcribe(
            apiKey = apiKey,
            wavData = segment.wavData,
            language = language,
            callback = object : DeepgramTranscriptionClient.TranscriptionCallback {
                override fun onTranscriptionComplete(text: String) {
                    completeTranscriptionRequest(requestToken, segment.sessionId) {
                        if (text.isNotBlank()) {
                            listener?.onTranscriptionResult(text)
                            // Schedule cleanup after transcription is inserted
                            startCleanupTimer()
                        }
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
            mainHandler.postDelayed(chunkWatchdogRunnable, CHUNK_WATCHDOG_MS)
        }
    }

    private fun cancelChunkWatchdog() {
        mainHandler.removeCallbacks(chunkWatchdogRunnable)
    }

    private fun startCleanupTimer() {
        mainHandler.removeCallbacks(cleanupTimerRunnable)
        mainHandler.postDelayed(cleanupTimerRunnable, CLEANUP_DELAY_MS)
    }

    private fun cancelCleanupTimer() {
        mainHandler.removeCallbacks(cleanupTimerRunnable)
    }

    private fun startNewParagraphTimer() {
        mainHandler.removeCallbacks(newParagraphTimerRunnable)
        if (currentState == State.RECORDING) {
            mainHandler.postDelayed(newParagraphTimerRunnable, NEW_PARAGRAPH_DELAY_MS)
        }
    }

    private fun cancelNewParagraphTimer() {
        mainHandler.removeCallbacks(newParagraphTimerRunnable)
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
