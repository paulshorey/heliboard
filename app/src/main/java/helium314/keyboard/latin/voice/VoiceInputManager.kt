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
 * 5. After transcription is inserted, cleanup is requested so the caller
 *    can send the current paragraph to Anthropic for post-processing.
 *
 * State machine: IDLE → RECORDING ↔ PAUSED → IDLE
 * (No CONNECTING state — recording starts instantly with no network dependency.)
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"

        /** Auto-cancel recording after this much total silence (ms). */
        private const val SILENCE_TIMEOUT_MS = 60_000L

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

    private val voiceRecorder = VoiceRecorder(context)
    private val transcriptionClient = DeepgramTranscriptionClient()
    private var listener: VoiceInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentState = State.IDLE

    // Silence timeout — auto-cancel after prolonged silence
    private val silenceTimeoutRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(TAG, "Silence timeout — cancelling recording")
            stopRecording()
            listener?.onError("Recording stopped — no speech detected")
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

        // Wire up the recorder callback
        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                updateState(State.RECORDING)
                resetSilenceTimeout()
            }

            override fun onSegmentReady(wavData: ByteArray) {
                // Send segment to Deepgram for transcription
                processSegment(wavData)
            }

            override fun onSpeechStarted() {
                cancelCleanupTimer()
                cancelNewParagraphTimer()
                resetSilenceTimeout()
            }

            override fun onSpeechStopped() {
                startNewParagraphTimer()
            }

            override fun onRecordingStopped() {
                Log.i(TAG, "Audio recording stopped")
            }

            override fun onRecordingError(error: String) {
                Log.e(TAG, "Recording error: $error")
                stopRecordingInternal()
                listener?.onError(error)
            }
        })

        if (!voiceRecorder.startRecording()) {
            Log.e(TAG, "Failed to start audio recording")
            updateState(State.IDLE)
            listener?.onError("Failed to start recording")
            return false
        }

        return true
    }

    fun stopRecording() {
        if (currentState == State.IDLE) return
        Log.i(TAG, "Stopping recording")
        stopRecordingInternal()
    }

    fun cancelRecording() {
        Log.i(TAG, "Cancelling recording")
        stopRecordingInternal()
    }

    fun pauseRecording() {
        if (currentState != State.RECORDING) return
        cancelSilenceTimeout()
        cancelCleanupTimer()
        cancelNewParagraphTimer()
        voiceRecorder.pauseRecording()
        updateState(State.PAUSED)
    }

    fun resumeRecording() {
        if (currentState != State.PAUSED) return
        voiceRecorder.resumeRecording()
        updateState(State.RECORDING)
        resetSilenceTimeout()
    }

    fun togglePause() {
        when (currentState) {
            State.RECORDING -> pauseRecording()
            State.PAUSED -> resumeRecording()
            else -> {}
        }
    }

    fun destroy() {
        stopRecordingInternal()
        listener = null
    }

    // ── Private helpers ────────────────────────────────────────────────

    private fun stopRecordingInternal() {
        cancelSilenceTimeout()
        cancelCleanupTimer()
        cancelNewParagraphTimer()
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
     * Send a completed audio segment to Deepgram, then deliver
     * the transcription to the listener and schedule cleanup.
     */
    private fun processSegment(wavData: ByteArray) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            listener?.onError("Deepgram API key not configured")
            return
        }

        val language = getCurrentLanguage()

        transcriptionClient.transcribe(
            apiKey = apiKey,
            wavData = wavData,
            language = language,
            callback = object : DeepgramTranscriptionClient.TranscriptionCallback {
                override fun onTranscriptionComplete(text: String) {
                    if (text.isNotBlank()) {
                        listener?.onTranscriptionResult(text)
                        // Schedule cleanup after transcription is inserted
                        startCleanupTimer()
                    }
                }

                override fun onTranscriptionError(error: String) {
                    Log.e(TAG, "Transcription error: $error")
                    listener?.onError(error)
                }
            }
        )
    }

    // ── Timers ─────────────────────────────────────────────────────────

    private fun resetSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        if (currentState == State.RECORDING) {
            mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS)
        }
    }

    private fun cancelSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
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
