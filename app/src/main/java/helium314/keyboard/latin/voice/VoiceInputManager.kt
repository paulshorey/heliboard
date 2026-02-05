// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the voice input workflow: recording audio and transcribing via Whisper API.
 *
 * Architecture: Recording and transcription are decoupled and run in parallel.
 * - Recording loop: continuously records, detects silence, saves file, immediately restarts
 * - Transcription: processes saved audio files asynchronously, inserts text when complete
 *
 * This allows the microphone to always be listening while transcriptions happen in background.
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"

        // Threshold for short transcription post-processing
        private const val SHORT_TEXT_THRESHOLD = 25

        // Common sentence punctuation to remove from short transcriptions
        private val SENTENCE_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', '…')

        // Duration of silence (in ms) after speech that triggers auto-stop
        private const val SILENCE_DURATION_MS = 3000L
    }

    /**
     * State represents the overall voice input state for UI purposes.
     * Note: Recording and transcription can happen simultaneously in continuous mode.
     */
    enum class State {
        IDLE,           // Not doing anything
        RECORDING,      // Actively recording (may also be transcribing in background)
        TRANSCRIBING    // Only transcribing (recording stopped, e.g., when user clicks to stop)
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        fun onTranscriptionResult(text: String)
        fun onTranscriptionProcessing()  // Called when audio is being sent to API
        fun onError(error: String)
        fun onPermissionRequired()
    }

    private val voiceRecorder = VoiceRecorder(context)
    private val whisperClient = WhisperApiClient()
    private var listener: VoiceInputListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Recording state
    private var isRecordingActive = false

    // Transcription state - track pending transcriptions
    private val pendingTranscriptions = AtomicInteger(0)

    // Continuous recording mode state
    private var continuousMode = false
    private var speechDetected = false
    private var silenceStartTime: Long = 0

    // Flag to skip post-processing (removing caps/punctuation) for auto-stopped recordings
    // Set to true when silence detection auto-stops, affects all transcriptions in the session
    private var skipPostProcessing = false

    val isRecording: Boolean
        get() = isRecordingActive

    val isPaused: Boolean
        get() = voiceRecorder.isCurrentlyPaused

    val isTranscribing: Boolean
        get() = pendingTranscriptions.get() > 0

    val isIdle: Boolean
        get() = !isRecordingActive && pendingTranscriptions.get() == 0

    /**
     * Get the current state for UI purposes.
     * In continuous mode, we prioritize showing RECORDING state.
     */
    val state: State
        get() = when {
            isRecordingActive -> State.RECORDING
            pendingTranscriptions.get() > 0 -> State.TRANSCRIBING
            else -> State.IDLE
        }

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
    }

    /**
     * Toggle recording state. If idle, starts recording. If recording, stops continuous mode
     * and completes the current transcription.
     */
    fun toggleRecording() {
        when {
            isRecordingActive -> {
                // Stop continuous mode and transcribe the current recording
                Log.i(TAG, "Toggle: stopping continuous mode and transcribing")
                continuousMode = false
                skipPostProcessing = false  // Reset for next session
                stopRecordingAndTranscribe()
            }
            pendingTranscriptions.get() > 0 -> {
                // Transcription in progress but not recording - just disable continuous mode
                Log.i(TAG, "Toggle: stopping continuous mode while transcribing")
                continuousMode = false
                skipPostProcessing = false  // Reset for next session
            }
            else -> {
                // Idle - start recording
                startRecording()
            }
        }
    }

    /**
     * Start recording audio.
     * @param continuous If true, enables continuous recording mode where recording
     *                   auto-restarts immediately after stopping (not waiting for transcription).
     */
    fun startRecording(continuous: Boolean = true): Boolean {
        if (isRecordingActive) {
            Log.w(TAG, "Cannot start recording, already recording")
            return false
        }

        if (!voiceRecorder.hasRecordPermission()) {
            Log.w(TAG, "No recording permission")
            listener?.onPermissionRequired()
            return false
        }

        // Set continuous mode and reset speech detection state
        continuousMode = continuous
        speechDetected = false
        silenceStartTime = 0
        // Reset post-processing flag - only skip if auto-stopped by silence detection
        skipPostProcessing = false

        setupRecorderCallback()
        return voiceRecorder.startRecording()
    }

    /**
     * Setup the recorder callback. Called when starting recording.
     */
    private fun setupRecorderCallback() {
        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                Log.i(TAG, "Recording started (continuous=$continuousMode, pendingTranscriptions=${pendingTranscriptions.get()})")
                isRecordingActive = true
                speechDetected = false
                silenceStartTime = 0
                listener?.onStateChanged(State.RECORDING)
            }

            override fun onRecordingStopped(audioFile: File?, averageRms: Double) {
                Log.i(TAG, "Recording stopped, file: ${audioFile?.absolutePath}, size: ${audioFile?.length() ?: 0}, rms: $averageRms")
                isRecordingActive = false

                // CRITICAL: In continuous mode, restart recording IMMEDIATELY
                // This happens BEFORE we start transcription
                if (continuousMode) {
                    Log.i(TAG, "Continuous mode: immediately restarting recording")
                    mainHandler.post {
                        if (continuousMode) {
                            startRecordingInternal()
                        } else {
                            // Continuous mode was cancelled while we were stopping
                            notifyStateChange()
                        }
                    }
                }

                // Now handle the audio file (transcription runs in parallel)
                if (averageRms < VoiceRecorder.MIN_RMS_THRESHOLD) {
                    Log.w(TAG, "Audio too quiet (rms: $averageRms), skipping transcription")
                    audioFile?.delete()
                    if (!continuousMode) {
                        listener?.onError("No speech detected - audio was too quiet")
                        notifyStateChange()
                    }
                    return
                }

                if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
                    Log.i(TAG, "Audio file valid, queuing transcription")
                    // Transcription runs in background, doesn't block recording
                    transcribeAudioAsync(audioFile)
                } else {
                    Log.e(TAG, "Audio file invalid or empty")
                    audioFile?.delete()
                    if (!continuousMode) {
                        listener?.onError("Recording failed - no audio captured")
                        notifyStateChange()
                    }
                }
            }

            override fun onRecordingError(error: String) {
                Log.e(TAG, "Recording error: $error")
                isRecordingActive = false
                continuousMode = false
                listener?.onStateChanged(State.IDLE)
                listener?.onError(error)
            }

            override fun onVolumeUpdate(currentRms: Double) {
                // Handle real-time volume updates for auto-pause detection
                // Don't process when not recording or when paused
                if (!isRecordingActive || isPaused) return

                val currentTime = System.currentTimeMillis()

                if (currentRms >= VoiceRecorder.SPEECH_RMS_THRESHOLD) {
                    // Speech detected
                    if (!speechDetected) {
                        Log.i(TAG, "Speech detected (rms: $currentRms)")
                    }
                    speechDetected = true
                    silenceStartTime = 0 // Reset silence timer
                } else if (speechDetected) {
                    // Silence after speech was detected
                    if (silenceStartTime == 0L) {
                        // Start tracking silence
                        silenceStartTime = currentTime
                        Log.i(TAG, "Silence started after speech (rms: $currentRms)")
                    } else {
                        // Check if silence has lasted long enough
                        val silenceDuration = currentTime - silenceStartTime
                        if (silenceDuration >= SILENCE_DURATION_MS) {
                            Log.i(TAG, "Auto-stopping after ${silenceDuration}ms of silence")
                            // Set flag to skip post-processing for all transcriptions in this session
                            // Since auto-stop was triggered, user is dictating continuously
                            skipPostProcessing = true
                            // Auto-stop recording - this will trigger transcription
                            // Recording will immediately restart in onRecordingStopped
                            stopRecordingInternal()
                        }
                    }
                }
            }
        })
    }

    /**
     * Internal method to start recording without resetting continuous mode.
     */
    private fun startRecordingInternal(): Boolean {
        if (isRecordingActive) {
            Log.w(TAG, "Cannot start recording internal, already recording")
            return false
        }

        speechDetected = false
        silenceStartTime = 0
        setupRecorderCallback()
        return voiceRecorder.startRecording()
    }

    /**
     * Internal method to stop recording without changing continuous mode.
     */
    private fun stopRecordingInternal() {
        if (!isRecordingActive) {
            Log.w(TAG, "Cannot stop recording, not recording")
            return
        }
        voiceRecorder.stopRecording()
    }

    /**
     * Stop recording and start transcription.
     */
    fun stopRecordingAndTranscribe() {
        if (!isRecordingActive) {
            Log.w(TAG, "Cannot stop recording, not recording")
            return
        }

        voiceRecorder.stopRecording()
        // Transcription is handled in the callback
    }

    /**
     * Cancel recording without transcribing.
     * This also stops continuous recording mode.
     */
    fun cancelRecording() {
        Log.i(TAG, "cancelRecording called, continuousMode was: $continuousMode")
        continuousMode = false
        speechDetected = false
        silenceStartTime = 0
        skipPostProcessing = false  // Reset for next session

        if (isRecordingActive) {
            voiceRecorder.cancelRecording()
            isRecordingActive = false
        }

        // Note: pending transcriptions will complete, but results won't restart recording
        notifyStateChange()
    }

    /**
     * Pause recording. Audio will not be captured while paused.
     * Auto-stop detection is also paused.
     */
    fun pauseRecording() {
        if (!isRecordingActive) {
            Log.w(TAG, "Cannot pause, not recording")
            return
        }
        voiceRecorder.pauseRecording()
        Log.i(TAG, "Recording paused")
    }

    /**
     * Resume recording after pause.
     */
    fun resumeRecording() {
        if (!isRecordingActive) {
            Log.w(TAG, "Cannot resume, not recording")
            return
        }
        voiceRecorder.resumeRecording()
        // Reset silence detection so we don't immediately auto-stop after resume
        speechDetected = false
        silenceStartTime = 0
        Log.i(TAG, "Recording resumed")
    }

    /**
     * Toggle pause state.
     */
    fun togglePause() {
        if (isPaused) {
            resumeRecording()
        } else {
            pauseRecording()
        }
    }

    /**
     * Stop continuous recording mode.
     * Recording will stop after the current segment completes.
     */
    fun stopContinuousMode() {
        Log.i(TAG, "stopContinuousMode called")
        continuousMode = false
    }

    /**
     * Check if continuous recording mode is active.
     */
    val isContinuousMode: Boolean
        get() = continuousMode

    /**
     * Notify listener of state change based on current recording/transcription status.
     */
    private fun notifyStateChange() {
        listener?.onStateChanged(state)
    }

    /**
     * Transcribe audio file asynchronously. Recording continues independently.
     */
    private fun transcribeAudioAsync(audioFile: File) {
        Log.i(TAG, "transcribeAudioAsync: file=${audioFile.absolutePath}, size=${audioFile.length()}")

        pendingTranscriptions.incrementAndGet()
        Log.i(TAG, "Pending transcriptions: ${pendingTranscriptions.get()}")

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank!")
            audioFile.delete()
            pendingTranscriptions.decrementAndGet()
            listener?.onError("OpenAI API key not configured. Please set it in Settings > Advanced.")
            notifyStateChange()
            return
        }

        // Get current keyboard language for better transcription
        val language = getCurrentLanguage()

        // Get custom prompt for transcription style
        val prompt = getPrompt()
        Log.i(TAG, "Starting Whisper API call with language: $language")

        // Notify listener that we're processing (sending to API)
        mainHandler.post {
            listener?.onTranscriptionProcessing()
        }

        whisperClient.transcribe(
            audioFile = audioFile,
            apiKey = apiKey,
            language = language,
            prompt = prompt.ifBlank { null },
            callback = object : WhisperApiClient.TranscriptionCallback {
                override fun onTranscriptionStarted() {
                    Log.i(TAG, "Transcription started for ${audioFile.name}")
                }

                override fun onTranscriptionComplete(text: String) {
                    Log.i(TAG, "Transcription complete: '$text'")

                    // Clean up this specific audio file
                    audioFile.delete()
                    pendingTranscriptions.decrementAndGet()
                    Log.i(TAG, "Pending transcriptions after completion: ${pendingTranscriptions.get()}")

                    if (text.isNotBlank()) {
                        // Post-process short transcriptions
                        val processedText = postProcessTranscription(text)
                        Log.i(TAG, "Delivering transcription result: '$processedText'")
                        listener?.onTranscriptionResult(processedText)
                    } else {
                        Log.w(TAG, "Transcription returned empty text")
                        // Don't show error - this is normal for silence segments
                    }

                    // Notify state change (might go to IDLE if this was last transcription)
                    if (!isRecordingActive) {
                        notifyStateChange()
                    }
                }

                override fun onTranscriptionError(error: String) {
                    Log.e(TAG, "Transcription error: $error")

                    // Clean up this specific audio file
                    audioFile.delete()
                    pendingTranscriptions.decrementAndGet()
                    Log.i(TAG, "Pending transcriptions after error: ${pendingTranscriptions.get()}")

                    listener?.onError(error)

                    // Notify state change
                    if (!isRecordingActive) {
                        notifyStateChange()
                    }
                }
            }
        )
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
            val defaultValue = Defaults.PREF_WHISPER_PROMPTS.getOrElse(selectedIndex) { "" }
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
     * Post-process transcription results.
     * For short texts (< 25 chars), removes sentence punctuation and converts to lowercase.
     * This helps with short voice inputs like "yes" or "okay" which Whisper tends to
     * return as "Yes." or "Okay."
     *
     * NOTE: This processing is SKIPPED when silence detection auto-stopped the recording,
     * because in continuous dictation mode we want to preserve the original formatting.
     */
    private fun postProcessTranscription(text: String): String {
        val trimmedText = text.trim()

        // Skip post-processing if auto-stopped by silence detection
        // In continuous dictation, preserve original capitalization and punctuation
        if (skipPostProcessing) {
            Log.i(TAG, "Skipping post-processing (auto-stopped session): '$trimmedText'")
            return trimmedText
        }

        // Only apply processing to short transcriptions
        if (trimmedText.length >= SHORT_TEXT_THRESHOLD) {
            return trimmedText
        }

        Log.i(TAG, "Applying short text processing to: '$trimmedText'")

        // Remove sentence punctuation and convert to lowercase
        val processed = trimmedText
            .filter { it !in SENTENCE_PUNCTUATION }
            .lowercase()
            .trim()

        Log.i(TAG, "Post-processed short text: '$processed'")
        return processed
    }

    /**
     * Clean up resources. Call when the keyboard is destroyed.
     */
    fun destroy() {
        continuousMode = false
        if (isRecordingActive) {
            voiceRecorder.cancelRecording()
            isRecordingActive = false
        }
        listener = null
    }
}
