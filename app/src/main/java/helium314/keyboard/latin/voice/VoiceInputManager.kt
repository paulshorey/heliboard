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
 * Manages the voice input workflow using OpenAI Realtime API for streaming transcription.
 *
 * Architecture:
 * - Recording: VoiceRecorder captures audio at 24kHz and streams chunks
 * - Transcription: RealtimeTranscriptionClient sends audio via WebSocket to OpenAI
 * - The server handles Voice Activity Detection (VAD) and returns transcriptions
 *
 * This provides real-time transcription without manual silence detection or file chunking.
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val SILENCE_TIMEOUT_MS = 30000L // 30 seconds - cancel recording
        private const val CLEANUP_DELAY_MS = 3000L // 3 seconds - trigger cleanup after silence
        private const val NEW_PARAGRAPH_DELAY_MS = 12000L // 12 seconds - start new paragraph after long silence
    }

    /**
     * State represents the overall voice input state for UI purposes.
     */
    enum class State {
        IDLE,           // Not doing anything
        CONNECTING,     // Connecting to Realtime API
        RECORDING,      // Actively recording and streaming
        PAUSED          // Recording paused (WebSocket still connected)
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        /** Called with complete transcription of a speech segment */
        fun onTranscriptionResult(text: String)
        /** Called with partial/incremental transcription (for real-time feedback) */
        fun onTranscriptionDelta(text: String)
        /** Called after 3 seconds of silence - time to cleanup the current paragraph */
        fun onCleanupRequested()
        /** Called after 12 seconds of silence - time to start a new paragraph */
        fun onNewParagraphRequested()
        fun onError(error: String)
        fun onPermissionRequired()
    }

    private val voiceRecorder = VoiceRecorder(context)
    private val realtimeClient = RealtimeTranscriptionClient()
    private var listener: VoiceInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // State tracking
    private var currentState = State.IDLE
    
    // Silence timeout - auto-cancel after 30 seconds of no speech
    private val silenceTimeoutRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(TAG, "Silence timeout - cancelling recording")
            cancelRecording()
            listener?.onError("Recording cancelled due to silence")
        }
    }
    
    // Cleanup timer - trigger cleanup after 3 seconds of silence
    private val cleanupTimerRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(TAG, "3 second silence - requesting cleanup")
            listener?.onCleanupRequested()
        }
    }
    
    // New paragraph timer - insert line breaks after 12 seconds of silence
    private val newParagraphTimerRunnable = Runnable {
        if (currentState == State.RECORDING) {
            Log.i(TAG, "12 second silence - requesting new paragraph")
            listener?.onNewParagraphRequested()
        }
    }

    val isRecording: Boolean
        get() = currentState == State.RECORDING

    val isPaused: Boolean
        get() = currentState == State.PAUSED

    val isIdle: Boolean
        get() = currentState == State.IDLE

    val state: State
        get() = currentState

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
    }

    /**
     * Toggle recording state.
     * - If idle: starts recording
     * - If recording: stops recording
     * - If paused: resumes recording
     */
    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PAUSED -> resumeRecording()
            State.CONNECTING -> {
                // Cancel connection attempt
                cancelRecording()
            }
        }
    }

    /**
     * Start recording and streaming to Realtime API.
     */
    fun startRecording(): Boolean {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Cannot start recording, current state: $currentState")
            return false
        }

        if (!voiceRecorder.hasRecordPermission()) {
            Log.w(TAG, "No recording permission")
            listener?.onPermissionRequired()
            return false
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank!")
            listener?.onError("OpenAI API key not configured. Please set it in Settings > Advanced.")
            return false
        }

        // Update state to connecting
        updateState(State.CONNECTING)

        // Get language and prompt settings
        val language = getCurrentLanguage()
        val prompt = getPrompt().ifBlank { null }

        Log.i(TAG, "Connecting to Realtime API...")

        // Connect to Realtime API
        realtimeClient.connect(
            apiKey = apiKey,
            language = language,
            prompt = prompt,
            callback = object : RealtimeTranscriptionClient.TranscriptionCallback {
                override fun onConnected() {
                    Log.i(TAG, "WebSocket connected")
                }

                override fun onSessionReady() {
                    Log.i(TAG, "Session ready, starting audio recording")
                    // Now start the audio recording
                    startAudioRecording()
                }

                override fun onTranscriptionDelta(text: String) {
                    resetSilenceTimeout()
                    listener?.onTranscriptionDelta(text)
                }

                override fun onTranscriptionComplete(text: String) {
                    resetSilenceTimeout()
                    if (text.isNotBlank()) {
                        listener?.onTranscriptionResult(text)
                    }
                }

                override fun onSpeechStarted() {
                    // User started speaking - cancel timers
                    cancelCleanupTimer()
                    cancelNewParagraphTimer()
                    resetSilenceTimeout()
                }

                override fun onSpeechStopped() {
                    // User stopped speaking - start silence timers
                    startCleanupTimer()        // 3 seconds - cleanup
                    startNewParagraphTimer()   // 12 seconds - new paragraph
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Realtime API error: $error")
                    listener?.onError(error)
                    // Don't stop recording on transient errors
                }

                override fun onDisconnected() {
                    Log.i(TAG, "WebSocket disconnected")
                    if (currentState != State.IDLE) {
                        // Unexpected disconnect
                        stopRecordingInternal()
                        listener?.onError("Connection lost")
                    }
                }
            }
        )

        return true
    }

    /**
     * Start the audio recording after WebSocket is ready.
     */
    private fun startAudioRecording() {
        voiceRecorder.setCallback(object : VoiceRecorder.StreamingCallback {
            override fun onRecordingStarted() {
                Log.i(TAG, "Audio recording started")
                updateState(State.RECORDING)
                // Start silence timeout
                resetSilenceTimeout()
            }

            override fun onAudioChunk(audioData: ByteArray) {
                // Stream audio to Realtime API
                if (currentState == State.RECORDING && realtimeClient.isReady) {
                    realtimeClient.sendAudio(audioData)
                }
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
            realtimeClient.disconnect()
            updateState(State.IDLE)
            listener?.onError("Failed to start recording")
        }
    }

    /**
     * Stop recording and close WebSocket connection.
     */
    fun stopRecording() {
        if (currentState == State.IDLE) {
            Log.w(TAG, "Already idle")
            return
        }

        Log.i(TAG, "Stopping recording")
        stopRecordingInternal()
    }

    /**
     * Internal method to stop everything.
     */
    private fun stopRecordingInternal() {
        cancelSilenceTimeout()
        cancelCleanupTimer()
        cancelNewParagraphTimer()
        voiceRecorder.stopRecording()
        realtimeClient.disconnect()
        updateState(State.IDLE)
    }

    /**
     * Cancel recording without waiting for final transcription.
     */
    fun cancelRecording() {
        Log.i(TAG, "Cancelling recording")
        realtimeClient.clearAudio()
        stopRecordingInternal()
    }

    /**
     * Pause recording. WebSocket stays connected but audio is not sent.
     */
    fun pauseRecording() {
        if (currentState != State.RECORDING) {
            Log.w(TAG, "Cannot pause, not recording")
            return
        }
        cancelSilenceTimeout()
        cancelCleanupTimer()
        cancelNewParagraphTimer()
        voiceRecorder.pauseRecording()
        updateState(State.PAUSED)
        Log.i(TAG, "Recording paused")
    }

    /**
     * Resume recording after pause.
     */
    fun resumeRecording() {
        if (currentState != State.PAUSED) {
            Log.w(TAG, "Cannot resume, not paused")
            return
        }
        voiceRecorder.resumeRecording()
        updateState(State.RECORDING)
        resetSilenceTimeout()
        Log.i(TAG, "Recording resumed")
    }

    /**
     * Toggle pause state.
     */
    fun togglePause() {
        when (currentState) {
            State.RECORDING -> pauseRecording()
            State.PAUSED -> resumeRecording()
            else -> Log.w(TAG, "Cannot toggle pause in state: $currentState")
        }
    }

    /**
     * Update state and notify listener.
     */
    private fun updateState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            listener?.onStateChanged(newState)
        }
    }
    
    /**
     * Reset the silence timeout. Called when speech activity is detected.
     */
    private fun resetSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
        if (currentState == State.RECORDING) {
            mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS)
        }
    }
    
    /**
     * Cancel the silence timeout.
     */
    private fun cancelSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable)
    }
    
    /**
     * Start the cleanup timer. Called when speech stops.
     */
    private fun startCleanupTimer() {
        mainHandler.removeCallbacks(cleanupTimerRunnable)
        if (currentState == State.RECORDING) {
            mainHandler.postDelayed(cleanupTimerRunnable, CLEANUP_DELAY_MS)
        }
    }
    
    /**
     * Cancel the cleanup timer. Called when speech starts again.
     */
    private fun cancelCleanupTimer() {
        mainHandler.removeCallbacks(cleanupTimerRunnable)
    }
    
    /**
     * Start the new paragraph timer. Called when speech stops.
     */
    private fun startNewParagraphTimer() {
        mainHandler.removeCallbacks(newParagraphTimerRunnable)
        if (currentState == State.RECORDING) {
            mainHandler.postDelayed(newParagraphTimerRunnable, NEW_PARAGRAPH_DELAY_MS)
        }
    }
    
    /**
     * Cancel the new paragraph timer. Called when speech starts again.
     */
    private fun cancelNewParagraphTimer() {
        mainHandler.removeCallbacks(newParagraphTimerRunnable)
    }

    private fun getApiKey(): String {
        return try {
            context.prefs().getString(Settings.PREF_WHISPER_API_KEY, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            ""
        }
    }

    private fun getPrompt(): String {
        return try {
            val prefs = context.prefs()
            // Get the selected preset index
            val selectedIndex = prefs.getInt(Settings.PREF_WHISPER_PROMPT_SELECTED, Defaults.PREF_WHISPER_PROMPT_SELECTED)
            // Get the prompt text for that index
            val key = Settings.PREF_WHISPER_PROMPT_PREFIX + selectedIndex
            val defaultValue = Defaults.PREF_TRANSCRIBE_PROMPTS.getOrElse(selectedIndex) { "" }
            prefs.getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prompt: ${e.message}")
            ""
        }
    }

    private fun getCurrentLanguage(): String? {
        return try {
            val settingsValues = Settings.getValues()
            val locale = settingsValues?.mLocale
            locale?.language
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current language: ${e.message}")
            null
        }
    }

    /**
     * Clean up resources. Call when the keyboard is destroyed.
     */
    fun destroy() {
        stopRecordingInternal()
        listener = null
    }
}
