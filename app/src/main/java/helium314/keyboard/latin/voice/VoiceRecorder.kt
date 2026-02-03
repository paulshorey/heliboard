// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Handles audio recording for voice-to-text functionality.
 * Records audio in WAV format suitable for Whisper API transcription.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 16000 // 16kHz - optimal for speech recognition
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    }

    interface RecordingCallback {
        fun onRecordingStarted()
        fun onRecordingStopped(audioFile: File?)
        fun onRecordingError(error: String)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var outputFile: File? = null
    private var callback: RecordingCallback? = null

    val isCurrentlyRecording: Boolean
        get() = isRecording

    fun setCallback(callback: RecordingCallback?) {
        this.callback = callback
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

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
            audioRecord = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                callback?.onRecordingError("Failed to initialize audio recording")
                releaseRecorder()
                return false
            }

            // Create temp file for recording
            outputFile = File.createTempFile("voice_recording_", ".wav", context.cacheDir)

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = thread(start = true) {
                writeAudioDataToFile(bufferSize)
            }

            callback?.onRecordingStarted()
            Log.i(TAG, "Recording started")
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

    fun stopRecording(): File? {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording")
            return null
        }

        isRecording = false

        try {
            recordingThread?.join(2000) // Wait up to 2 seconds for thread to finish
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for recording thread")
        }

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        releaseRecorder()

        val file = outputFile
        if (file != null && file.exists() && file.length() > 44) { // > WAV header size
            // Write WAV header
            writeWavHeader(file)
            Log.i(TAG, "Recording stopped, file: ${file.absolutePath}, size: ${file.length()}")
            callback?.onRecordingStopped(file)
            return file
        } else {
            Log.w(TAG, "Recording file is empty or doesn't exist")
            callback?.onRecordingStopped(null)
            return null
        }
    }

    fun cancelRecording() {
        if (!isRecording) return

        isRecording = false

        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            // Ignore
        }

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        releaseRecorder()

        // Delete temp file
        outputFile?.delete()
        outputFile = null
        callback?.onRecordingStopped(null)
        Log.i(TAG, "Recording cancelled")
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

    private fun writeAudioDataToFile(bufferSize: Int) {
        val data = ByteArray(bufferSize)
        val file = outputFile ?: return

        try {
            FileOutputStream(file).use { fos ->
                // Write placeholder WAV header (44 bytes)
                fos.write(ByteArray(44))

                while (isRecording) {
                    val read = audioRecord?.read(data, 0, bufferSize) ?: break
                    if (read > 0) {
                        fos.write(data, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data: ${e.message}")
        }
    }

    private fun writeWavHeader(file: File) {
        try {
            val fileSize = file.length()
            val dataSize = fileSize - 44

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)

                // RIFF header
                raf.writeBytes("RIFF")
                raf.write(intToByteArrayLE((fileSize - 8).toInt()))
                raf.writeBytes("WAVE")

                // fmt subchunk
                raf.writeBytes("fmt ")
                raf.write(intToByteArrayLE(16)) // Subchunk1 size (16 for PCM)
                raf.write(shortToByteArrayLE(1)) // Audio format (1 = PCM)
                raf.write(shortToByteArrayLE(1)) // Number of channels (1 = mono)
                raf.write(intToByteArrayLE(SAMPLE_RATE)) // Sample rate
                raf.write(intToByteArrayLE(SAMPLE_RATE * 2)) // Byte rate (SampleRate * NumChannels * BitsPerSample/8)
                raf.write(shortToByteArrayLE(2)) // Block align (NumChannels * BitsPerSample/8)
                raf.write(shortToByteArrayLE(16)) // Bits per sample

                // data subchunk
                raf.writeBytes("data")
                raf.write(intToByteArrayLE(dataSize.toInt()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing WAV header: ${e.message}")
        }
    }

    private fun intToByteArrayLE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArrayLE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
}
