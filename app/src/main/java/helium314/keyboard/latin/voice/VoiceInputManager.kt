// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File

/**
 * Manages the voice input workflow: recording audio and transcribing via Whisper API.
 * Coordinates between VoiceRecorder and WhisperApiClient.
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

    enum class State {
        IDLE,
        RECORDING,
        TRANSCRIBING
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        fun onTranscriptionResult(text: String)
        fun onError(error: String)
        fun onPermissionRequired()
    }

    private val voiceRecorder = VoiceRecorder(context)
    private val whisperClient = WhisperApiClient()
    private var listener: VoiceInputListener? = null
    private var currentState = State.IDLE
    private var currentAudioFile: File? = null

    // Continuous recording mode state
    private var continuousMode = false
    private var speechDetected = false
    private var silenceStartTime: Long = 0

    val isRecording: Boolean
        get() = currentState == State.RECORDING

    val isTranscribing: Boolean
        get() = currentState == State.TRANSCRIBING

    val isIdle: Boolean
        get() = currentState == State.IDLE

    val state: State
        get() = currentState

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
    }

    /**
     * Toggle recording state. If idle, starts recording. If recording, stops continuous mode
     * and completes the current transcription.
     */
    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> {
                // Stop continuous mode and transcribe the current recording
                Log.i(TAG, "Toggle: stopping continuous mode and transcribing")
                continuousMode = false
                stopRecordingAndTranscribe()
            }
            State.TRANSCRIBING -> {
                // If in continuous mode while transcribing, stop the mode
                // The current transcription will finish but won't restart
                if (continuousMode) {
                    Log.i(TAG, "Toggle: stopping continuous mode while transcribing")
                    continuousMode = false
                } else {
                    Log.w(TAG, "Cannot toggle while transcribing (not in continuous mode)")
                }
            }
        }
    }

    /**
     * Start recording audio.
     * @param continuous If true, enables continuous recording mode where recording
     *                   auto-restarts after transcription until manually cancelled.
     */
    fun startRecording(continuous: Boolean = true): Boolean {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Cannot start recording, current state: $currentState")
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

        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                Log.i(TAG, "Recording started callback received (continuous=$continuousMode)")
                currentState = State.RECORDING
                speechDetected = false
                silenceStartTime = 0
                listener?.onStateChanged(State.RECORDING)
            }

            override fun onRecordingStopped(audioFile: File?, averageRms: Double) {
                Log.i(TAG, "Recording stopped callback received, audioFile: ${audioFile?.absolutePath}, size: ${audioFile?.length() ?: 0}, rms: $averageRms")
                currentAudioFile = audioFile

                // Check if audio is too quiet (likely silence or noise)
                if (averageRms < VoiceRecorder.MIN_RMS_THRESHOLD) {
                    Log.w(TAG, "Audio too quiet (rms: $averageRms < threshold: ${VoiceRecorder.MIN_RMS_THRESHOLD}), cancelling transcription")

                    // In continuous mode, restart recording instead of going to idle
                    if (continuousMode) {
                        Log.i(TAG, "Continuous mode: restarting recording after silence")
                        cleanupAudioFile()
                        // Small delay before restarting
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (continuousMode) {
                                startRecordingInternal()
                            }
                        }, 100)
                        return
                    }

                    currentState = State.IDLE
                    listener?.onStateChanged(State.IDLE)
                    listener?.onError("No speech detected - audio was too quiet")
                    cleanupAudioFile()
                    return
                }

                if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
                    Log.i(TAG, "Audio file valid, starting transcription")
                    transcribeAudio(audioFile)
                } else {
                    Log.e(TAG, "Audio file invalid or empty: exists=${audioFile?.exists()}, size=${audioFile?.length()}")

                    // In continuous mode, restart recording
                    if (continuousMode) {
                        Log.i(TAG, "Continuous mode: restarting recording after empty file")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (continuousMode) {
                                startRecordingInternal()
                            }
                        }, 100)
                        return
                    }

                    currentState = State.IDLE
                    listener?.onStateChanged(State.IDLE)
                    listener?.onError("Recording failed - no audio captured (file size: ${audioFile?.length() ?: 0} bytes)")
                }
            }

            override fun onRecordingError(error: String) {
                Log.e(TAG, "Recording error: $error")
                continuousMode = false
                currentState = State.IDLE
                listener?.onStateChanged(State.IDLE)
                listener?.onError(error)
            }

            override fun onVolumeUpdate(currentRms: Double) {
                // Handle real-time volume updates for auto-pause detection
                if (currentState != State.RECORDING) return

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
                            // Auto-stop recording - this will trigger transcription
                            stopRecordingAndTranscribe()
                        }
                    }
                }
            }
        })

        return voiceRecorder.startRecording()
    }

    /**
     * Internal method to start recording without resetting continuous mode.
     */
    private fun startRecordingInternal(): Boolean {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Cannot start recording internal, current state: $currentState")
            return false
        }

        speechDetected = false
        silenceStartTime = 0
        return voiceRecorder.startRecording()
    }

    /**
     * Stop recording and start transcription.
     */
    fun stopRecordingAndTranscribe() {
        if (currentState != State.RECORDING) {
            Log.w(TAG, "Cannot stop recording, current state: $currentState")
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

        if (currentState == State.RECORDING) {
            voiceRecorder.cancelRecording()
            currentState = State.IDLE
            listener?.onStateChanged(State.IDLE)
        } else if (currentState == State.TRANSCRIBING) {
            // If transcribing, just mark that continuous mode is off
            // The transcription will finish but won't restart recording
            Log.i(TAG, "Cancelling continuous mode while transcribing")
        }
    }

    /**
     * Stop continuous recording mode.
     * Recording will stop after the current transcription completes.
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

    private fun transcribeAudio(audioFile: File) {
        Log.i(TAG, "transcribeAudio called with file: ${audioFile.absolutePath}, size: ${audioFile.length()}")
        currentState = State.TRANSCRIBING
        listener?.onStateChanged(State.TRANSCRIBING)

        val apiKey = getApiKey()
        Log.i(TAG, "API key retrieved, length: ${apiKey.length}, blank: ${apiKey.isBlank()}")
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank!")
            currentState = State.IDLE
            listener?.onStateChanged(State.IDLE)
            listener?.onError("OpenAI API key not configured. Please set it in Settings > Advanced.")
            cleanupAudioFile()
            return
        }

        // Get current keyboard language for better transcription
        val language = getCurrentLanguage()

        // Get custom prompt for transcription style
        val prompt = getPrompt()
        Log.i(TAG, "Starting Whisper API call with language: $language, prompt: '${prompt.take(50)}...'")

        whisperClient.transcribe(
            audioFile = audioFile,
            apiKey = apiKey,
            language = language,
            prompt = prompt.ifBlank { null },
            callback = object : WhisperApiClient.TranscriptionCallback {
                override fun onTranscriptionStarted() {
                    Log.i(TAG, "Transcription started")
                }

                override fun onTranscriptionComplete(text: String) {
                    Log.i(TAG, "Transcription complete, text length: ${text.length}, text: '$text'")

                    if (text.isNotBlank()) {
                        // Post-process short transcriptions
                        val processedText = postProcessTranscription(text)
                        Log.i(TAG, "Calling onTranscriptionResult with text: '$processedText'")
                        listener?.onTranscriptionResult(processedText)
                    } else {
                        Log.w(TAG, "Transcription returned empty text")
                        // Don't show error in continuous mode for empty transcriptions
                        if (!continuousMode) {
                            listener?.onError("No speech detected in recording")
                        }
                    }

                    cleanupAudioFile()

                    // In continuous mode, restart recording after transcription
                    if (continuousMode) {
                        Log.i(TAG, "Continuous mode: restarting recording after transcription")
                        currentState = State.IDLE
                        // Small delay before restarting to allow UI update
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (continuousMode) {
                                startRecordingInternal()
                            }
                        }, 100)
                    } else {
                        currentState = State.IDLE
                        listener?.onStateChanged(State.IDLE)
                    }
                }

                override fun onTranscriptionError(error: String) {
                    Log.e(TAG, "Transcription error: $error")
                    cleanupAudioFile()

                    // In continuous mode, try to restart recording even after error
                    if (continuousMode) {
                        Log.i(TAG, "Continuous mode: restarting recording after transcription error")
                        currentState = State.IDLE
                        listener?.onError(error)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (continuousMode) {
                                startRecordingInternal()
                            }
                        }, 500)
                    } else {
                        currentState = State.IDLE
                        listener?.onStateChanged(State.IDLE)
                        listener?.onError(error)
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

    private fun cleanupAudioFile() {
        try {
            currentAudioFile?.delete()
            currentAudioFile = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up audio file: ${e.message}")
        }
    }

    /**
     * Post-process transcription results.
     * For short texts (< 25 chars), removes sentence punctuation and converts to lowercase.
     * This helps with short voice inputs like "yes" or "okay" which Whisper tends to
     * return as "Yes." or "Okay."
     */
    private fun postProcessTranscription(text: String): String {
        val trimmedText = text.trim()

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
        if (currentState == State.RECORDING) {
            voiceRecorder.cancelRecording()
        }
        cleanupAudioFile()
        listener = null
    }
}
