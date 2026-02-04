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
 * Supports both single-shot and continuous recording modes.
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"

        // Threshold for short transcription post-processing
        private const val SHORT_TEXT_THRESHOLD = 25

        // Common sentence punctuation to remove from short transcriptions
        private val SENTENCE_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', '…')
    }

    enum class State {
        IDLE,
        RECORDING,              // Single-shot recording
        TRANSCRIBING,           // Transcribing single recording
        CONTINUOUS_RECORDING,   // Continuous mode: recording and may be transcribing simultaneously
    }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        fun onTranscriptionResult(text: String)
        fun onError(error: String)
        fun onPermissionRequired()
        // Continuous mode callbacks
        fun onSpeechDetected() {}
        fun onSilenceDetected() {}
    }

    private val voiceRecorder = VoiceRecorder(context)
    private val whisperClient = WhisperApiClient()
    private var listener: VoiceInputListener? = null
    private var currentState = State.IDLE
    private var currentAudioFile: File? = null

    // Continuous mode tracking
    private var pendingTranscriptions = 0
    private val pendingAudioFiles = mutableListOf<File>()

    val isRecording: Boolean
        get() = currentState == State.RECORDING || currentState == State.CONTINUOUS_RECORDING

    val isTranscribing: Boolean
        get() = currentState == State.TRANSCRIBING || pendingTranscriptions > 0

    val isIdle: Boolean
        get() = currentState == State.IDLE

    val isContinuousRecording: Boolean
        get() = currentState == State.CONTINUOUS_RECORDING

    val state: State
        get() = currentState

    fun setListener(listener: VoiceInputListener?) {
        this.listener = listener
    }

    /**
     * Toggle recording state. If idle, starts continuous recording. If recording, stops.
     */
    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startContinuousRecording()  // Default to continuous mode
            State.RECORDING -> stopRecordingAndTranscribe()
            State.CONTINUOUS_RECORDING -> stopContinuousRecording()
            State.TRANSCRIBING -> {
                // Can't toggle while transcribing - ignore
                Log.w(TAG, "Cannot toggle while transcribing")
            }
        }
    }

    /**
     * Toggle single-shot recording (old behavior).
     */
    fun toggleSingleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecordingAndTranscribe()
            State.CONTINUOUS_RECORDING -> stopContinuousRecording()
            State.TRANSCRIBING -> {
                Log.w(TAG, "Cannot toggle while transcribing")
            }
        }
    }

    /**
     * Start recording audio (single-shot mode).
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

        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                Log.i(TAG, "Recording started callback received")
                currentState = State.RECORDING
                listener?.onStateChanged(State.RECORDING)
            }

            override fun onRecordingStopped(audioFile: File?, averageRms: Double) {
                Log.i(TAG, "Recording stopped callback received, audioFile: ${audioFile?.absolutePath}, size: ${audioFile?.length() ?: 0}, rms: $averageRms")
                currentAudioFile = audioFile

                // Check if audio is too quiet (likely silence or noise)
                if (averageRms < VoiceRecorder.MIN_RMS_THRESHOLD) {
                    Log.w(TAG, "Audio too quiet (rms: $averageRms < threshold: ${VoiceRecorder.MIN_RMS_THRESHOLD}), cancelling transcription")
                    currentState = State.IDLE
                    listener?.onStateChanged(State.IDLE)
                    listener?.onError("No speech detected - audio was too quiet")
                    cleanupAudioFile()
                    return
                }

                if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
                    Log.i(TAG, "Audio file valid, starting transcription")
                    transcribeAudio(audioFile, isContinuousMode = false)
                } else {
                    Log.e(TAG, "Audio file invalid or empty: exists=${audioFile?.exists()}, size=${audioFile?.length()}")
                    currentState = State.IDLE
                    listener?.onStateChanged(State.IDLE)
                    listener?.onError("Recording failed - no audio captured (file size: ${audioFile?.length() ?: 0} bytes)")
                }
            }

            override fun onRecordingError(error: String) {
                Log.e(TAG, "Recording error: $error")
                currentState = State.IDLE
                listener?.onStateChanged(State.IDLE)
                listener?.onError(error)
            }
        })

        return voiceRecorder.startRecording()
    }

    /**
     * Start continuous recording mode.
     * Recording will automatically chunk based on speech/silence detection.
     * Each chunk is transcribed as it becomes ready, and recording continues.
     */
    fun startContinuousRecording(): Boolean {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Cannot start continuous recording, current state: $currentState")
            return false
        }

        if (!voiceRecorder.hasRecordPermission()) {
            Log.w(TAG, "No recording permission")
            listener?.onPermissionRequired()
            return false
        }

        // Reset continuous mode tracking
        pendingTranscriptions = 0
        pendingAudioFiles.clear()

        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                Log.i(TAG, "Continuous recording started")
                currentState = State.CONTINUOUS_RECORDING
                listener?.onStateChanged(State.CONTINUOUS_RECORDING)
            }

            override fun onRecordingStopped(audioFile: File?) {
                Log.i(TAG, "Continuous recording stopped, final chunk: ${audioFile?.absolutePath}")
                // Handle the final chunk when user stops recording
                if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
                    transcribeAudio(audioFile, isContinuousMode = true)
                } else {
                    // No speech in final chunk, check if we have pending transcriptions
                    if (pendingTranscriptions == 0) {
                        currentState = State.IDLE
                        listener?.onStateChanged(State.IDLE)
                    }
                    // If there are pending transcriptions, state will be updated when they complete
                }
            }

            override fun onRecordingError(error: String) {
                Log.e(TAG, "Recording error: $error")
                currentState = State.IDLE
                listener?.onStateChanged(State.IDLE)
                listener?.onError(error)
            }

            override fun onChunkReady(audioFile: File) {
                // A chunk is ready for transcription while recording continues
                Log.i(TAG, "Chunk ready for transcription: ${audioFile.absolutePath}, size: ${audioFile.length()}")
                transcribeAudio(audioFile, isContinuousMode = true)
            }

            override fun onSpeechDetected() {
                Log.i(TAG, "Speech detected")
                listener?.onSpeechDetected()
            }

            override fun onSilenceDetected() {
                Log.i(TAG, "Silence detected")
                listener?.onSilenceDetected()
            }
        })

        return voiceRecorder.startContinuousRecording()
    }

    /**
     * Stop continuous recording.
     * Any remaining audio with speech will be transcribed.
     */
    fun stopContinuousRecording() {
        if (currentState != State.CONTINUOUS_RECORDING) {
            Log.w(TAG, "Not in continuous recording mode, current state: $currentState")
            return
        }

        Log.i(TAG, "Stopping continuous recording")
        voiceRecorder.stopRecording()
        // State transition is handled in onRecordingStopped callback
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
     */
    fun cancelRecording() {
        if (currentState == State.RECORDING || currentState == State.CONTINUOUS_RECORDING) {
            voiceRecorder.cancelRecording()
            pendingTranscriptions = 0
            pendingAudioFiles.forEach { it.delete() }
            pendingAudioFiles.clear()
            currentState = State.IDLE
            listener?.onStateChanged(State.IDLE)
        }
    }

    private fun transcribeAudio(audioFile: File, isContinuousMode: Boolean) {
        Log.i(TAG, "transcribeAudio called with file: ${audioFile.absolutePath}, size: ${audioFile.length()}, continuous: $isContinuousMode")

        if (!isContinuousMode) {
            currentState = State.TRANSCRIBING
            listener?.onStateChanged(State.TRANSCRIBING)
        } else {
            // Track pending transcriptions in continuous mode
            pendingTranscriptions++
            pendingAudioFiles.add(audioFile)
            Log.i(TAG, "Pending transcriptions: $pendingTranscriptions")
        }

        val apiKey = getApiKey()
        Log.i(TAG, "API key retrieved, length: ${apiKey.length}, blank: ${apiKey.isBlank()}")
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank!")
            handleTranscriptionComplete(audioFile, isContinuousMode, null, "OpenAI API key not configured. Please set it in Settings > Advanced.")
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
                    Log.i(TAG, "Transcription started for ${audioFile.name}")
                }

                override fun onTranscriptionComplete(text: String) {
                    Log.i(TAG, "Transcription complete for ${audioFile.name}, text: '$text'")
                    handleTranscriptionComplete(audioFile, isContinuousMode, text, null)
                }

                override fun onTranscriptionError(error: String) {
                    Log.e(TAG, "Transcription error for ${audioFile.name}: $error")
                    handleTranscriptionComplete(audioFile, isContinuousMode, null, error)
                }
            }
        )
    }

    /**
     * Handle transcription completion for both single-shot and continuous modes.
     */
    private fun handleTranscriptionComplete(audioFile: File, isContinuousMode: Boolean, text: String?, error: String?) {
        // Clean up the audio file
        audioFile.delete()
        pendingAudioFiles.remove(audioFile)

        if (isContinuousMode) {
            pendingTranscriptions--
            Log.i(TAG, "Continuous mode transcription complete, pending: $pendingTranscriptions")

            if (text != null && text.isNotBlank()) {
                // Post-process and deliver result
                val processedText = postProcessTranscription(text)
                Log.i(TAG, "Delivering transcription result: '$processedText'")
                listener?.onTranscriptionResult(processedText)
            } else if (error != null) {
                listener?.onError(error)
            }

            // Check if we should transition to IDLE
            // Only go idle if recording has stopped AND no pending transcriptions
            if (!voiceRecorder.isCurrentlyRecording && pendingTranscriptions == 0) {
                currentState = State.IDLE
                listener?.onStateChanged(State.IDLE)
            }
        } else {
            // Single-shot mode
            currentState = State.IDLE
            listener?.onStateChanged(State.IDLE)

            if (text != null && text.isNotBlank()) {
                val processedText = postProcessTranscription(text)
                Log.i(TAG, "Calling onTranscriptionResult with text: '$processedText'")
                listener?.onTranscriptionResult(processedText)
            } else if (error != null) {
                listener?.onError(error)
            } else {
                listener?.onError("No speech detected in recording")
            }

            cleanupAudioFile()
        }
    }

    /**
     * Post-process transcription results.
     * For short texts (< 25 chars), removes sentence punctuation and converts to lowercase.
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
        if (currentState == State.RECORDING || currentState == State.CONTINUOUS_RECORDING) {
            voiceRecorder.cancelRecording()
        }
        cleanupAudioFile()
        // Clean up any pending audio files
        pendingAudioFiles.forEach { it.delete() }
        pendingAudioFiles.clear()
        pendingTranscriptions = 0
        listener = null
    }
}
