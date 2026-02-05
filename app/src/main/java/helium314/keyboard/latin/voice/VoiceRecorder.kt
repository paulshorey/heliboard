// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.utils.Log
import kotlin.concurrent.thread

/**
 * Handles audio recording for real-time voice-to-text streaming.
 * Streams audio in PCM16 format suitable for OpenAI Realtime API.
 *
 * Audio format requirements:
 * - Sample rate: 24kHz (required by Realtime API)
 * - Channels: Mono
 * - Format: 16-bit PCM (signed, little-endian)
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"

        // 24kHz - required by OpenAI Realtime API
        const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

        // How often to send audio chunks (in milliseconds)
        // Smaller = more responsive, larger = more efficient
        private const val CHUNK_INTERVAL_MS = 100L

        // Calculate bytes per chunk: 24000 samples/sec * 2 bytes/sample * 0.1 sec = 4800 bytes
        private const val BYTES_PER_CHUNK = (SAMPLE_RATE * 2 * CHUNK_INTERVAL_MS / 1000).toInt()
    }

    /**
     * Callback interface for streaming audio data.
     */
    interface StreamingCallback {
        /** Called when recording starts successfully */
        fun onRecordingStarted()

        /** Called with audio chunks for streaming. Audio is PCM16, 24kHz, mono. */
        fun onAudioChunk(audioData: ByteArray)

        /** Called when recording stops */
        fun onRecordingStopped()

        /** Called when an error occurs */
        fun onRecordingError(error: String)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var isPaused = false
    private var callback: StreamingCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isCurrentlyRecording: Boolean
        get() = isRecording

    val isCurrentlyPaused: Boolean
        get() = isPaused

    fun setCallback(callback: StreamingCallback?) {
        this.callback = callback
    }

    /**
     * Pause recording. Audio capture continues but data is discarded.
     */
    fun pauseRecording() {
        if (!isRecording) {
            Log.w(TAG, "Cannot pause, not recording")
            return
        }
        if (isPaused) {
            Log.w(TAG, "Already paused")
            return
        }
        isPaused = true
        Log.i(TAG, "Recording paused")
    }

    /**
     * Resume recording after pause.
     */
    fun resumeRecording() {
        if (!isRecording) {
            Log.w(TAG, "Cannot resume, not recording")
            return
        }
        if (!isPaused) {
            Log.w(TAG, "Not paused")
            return
        }
        isPaused = false
        Log.i(TAG, "Recording resumed")
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording and streaming audio.
     * Audio chunks are delivered via the callback.
     */
    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        if (!hasRecordPermission()) {
            Log.e(TAG, "No RECORD_AUDIO permission")
            callback?.onRecordingError("Microphone permission not granted")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            callback?.onRecordingError("Failed to initialize audio recording")
            return false
        }

        try {
            // Use larger buffer for smoother streaming
            val actualBufferSize = maxOf(bufferSize * 2, BYTES_PER_CHUNK * 2)

            audioRecord = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                callback?.onRecordingError("Failed to initialize audio recording")
                releaseRecorder()
                return false
            }

            isPaused = false
            audioRecord?.startRecording()
            isRecording = true

            recordingThread = thread(start = true) {
                streamAudioData()
            }

            mainHandler.post { callback?.onRecordingStarted() }
            Log.i(TAG, "Recording started (streaming mode, ${SAMPLE_RATE}Hz)")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting recording: ${e.message}")
            callback?.onRecordingError("Microphone permission denied")
            releaseRecorder()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting recording: ${e.message}")
            callback?.onRecordingError("Failed to start recording: ${e.message}")
            releaseRecorder()
            return false
        }
    }

    /**
     * Stop recording.
     */
    fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording")
            return
        }

        isRecording = false

        try {
            recordingThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for recording thread")
        }

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        releaseRecorder()

        Log.i(TAG, "Recording stopped")
        mainHandler.post { callback?.onRecordingStopped() }
    }

    /**
     * Cancel recording (same as stop for streaming).
     */
    fun cancelRecording() {
        stopRecording()
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
        recordingThread = null
    }

    /**
     * Continuously read audio data and stream to callback.
     */
    private fun streamAudioData() {
        val chunkBuffer = ByteArray(BYTES_PER_CHUNK)

        try {
            while (isRecording) {
                val read = audioRecord?.read(chunkBuffer, 0, BYTES_PER_CHUNK) ?: break

                if (read > 0 && !isPaused) {
                    // Create a properly sized copy of the audio data
                    val audioChunk = if (read == BYTES_PER_CHUNK) {
                        chunkBuffer.copyOf()
                    } else {
                        chunkBuffer.copyOf(read)
                    }

                    // Deliver audio chunk to callback
                    mainHandler.post {
                        callback?.onAudioChunk(audioChunk)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming audio: ${e.message}")
            mainHandler.post {
                callback?.onRecordingError("Error streaming audio: ${e.message}")
            }
        }
    }
}
