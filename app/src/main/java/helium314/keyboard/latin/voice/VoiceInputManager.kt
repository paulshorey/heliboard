// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
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
     * Toggle recording state. If idle, starts recording. If recording, stops and transcribes.
     */
    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecordingAndTranscribe()
            State.TRANSCRIBING -> {
                // Can't toggle while transcribing - ignore
                Log.w(TAG, "Cannot toggle while transcribing")
            }
        }
    }

    /**
     * Start recording audio.
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

            override fun onRecordingStopped(audioFile: File?) {
                Log.i(TAG, "Recording stopped callback received, audioFile: ${audioFile?.absolutePath}, size: ${audioFile?.length() ?: 0}")
                currentAudioFile = audioFile
                if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
                    Log.i(TAG, "Audio file valid, starting transcription")
                    transcribeAudio(audioFile)
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
        if (currentState == State.RECORDING) {
            voiceRecorder.cancelRecording()
            currentState = State.IDLE
            listener?.onStateChanged(State.IDLE)
        }
    }

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
                    currentState = State.IDLE
                    listener?.onStateChanged(State.IDLE)

                    if (text.isNotBlank()) {
                        Log.i(TAG, "Calling onTranscriptionResult with text")
                        listener?.onTranscriptionResult(text)
                    } else {
                        Log.w(TAG, "Transcription returned empty text")
                        listener?.onError("No speech detected in recording")
                    }

                    cleanupAudioFile()
                }

                override fun onTranscriptionError(error: String) {
                    Log.e(TAG, "Transcription error: $error")
                    currentState = State.IDLE
                    listener?.onStateChanged(State.IDLE)
                    listener?.onError(error)
                    cleanupAudioFile()
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
            context.prefs().getString(Settings.PREF_WHISPER_PROMPT, "") ?: ""
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
     * Clean up resources. Call when the keyboard is destroyed.
     */
    fun destroy() {
        if (currentState == State.RECORDING) {
            voiceRecorder.cancelRecording()
        }
        cleanupAudioFile()
        listener = null
    }
}
