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
 * Supports continuous recording mode with automatic silence detection.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 16000 // 16kHz - optimal for speech recognition
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

        // Volume detection thresholds
        // 16-bit audio has max amplitude of 32767
        // Speech typically has RMS of 500-5000, silence/noise is below 300
        const val SPEECH_THRESHOLD_RMS = 400.0  // Above this = speech detected
        const val SILENCE_THRESHOLD_RMS = 300.0 // Below this = silence detected

        // Minimum RMS threshold for single-shot mode - below this, recording is considered too quiet
        // Used to reject recordings that are mostly silence/noise
        const val MIN_RMS_THRESHOLD = 500.0

        // Timing constants for continuous mode (in milliseconds)
        const val MIN_SPEECH_DURATION_MS = 3000L  // Minimum speech before considering silence
        const val SILENCE_DURATION_FOR_CHUNK_MS = 3000L  // Silence duration to trigger chunk
        const val VOLUME_CHECK_INTERVAL_MS = 100L // How often to check volume (100ms chunks)
    }

    interface RecordingCallback {
        fun onRecordingStarted()
        fun onRecordingStopped(audioFile: File?, averageRms: Double)
        fun onRecordingError(error: String)
        // Continuous mode callbacks
        fun onChunkReady(audioFile: File) {}  // Called when a speech chunk is ready for transcription
        fun onSpeechDetected() {}  // Called when speech starts
        fun onSilenceDetected() {}  // Called when silence starts after speech
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var isContinuousMode = false
    private var outputFile: File? = null
    private var callback: RecordingCallback? = null

    // Volume and speech detection state
    private var currentRms: Double = 0.0
    private var isSpeaking = false
    private var speechStartTime: Long = 0L
    private var silenceStartTime: Long = 0L
    private var hasSpeechInCurrentChunk = false
    private var chunkCounter = 0

    // Volume tracking for single-shot mode RMS calculation
    private var sumSquares: Double = 0.0
    private var sampleCount: Long = 0
    private var averageRms: Double = 0.0

    val isCurrentlyRecording: Boolean
        get() = isRecording

    val isInContinuousMode: Boolean
        get() = isContinuousMode

    fun setCallback(callback: RecordingCallback?) {
        this.callback = callback
    }

    /**
     * Reset speech detection state for a new chunk
     */
    private fun resetSpeechState() {
        isSpeaking = false
        speechStartTime = 0L
        silenceStartTime = 0L
        hasSpeechInCurrentChunk = false
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording in normal (single-shot) mode.
     */
    fun startRecording(): Boolean {
        return startRecordingInternal(continuousMode = false)
    }

    /**
     * Start recording in continuous mode.
     * In this mode, recording automatically chunks based on speech/silence detection.
     * After detecting speech followed by silence, it saves the chunk and starts a new one.
     */
    fun startContinuousRecording(): Boolean {
        return startRecordingInternal(continuousMode = true)
    }

    private fun startRecordingInternal(continuousMode: Boolean): Boolean {
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
            chunkCounter = 0
            outputFile = File.createTempFile("voice_chunk_${chunkCounter}_", ".wav", context.cacheDir)

            // Reset state
            isContinuousMode = continuousMode
            resetSpeechState()

            // Reset volume tracking
            sumSquares = 0.0
            sampleCount = 0
            averageRms = 0.0

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = thread(start = true) {
                if (continuousMode) {
                    writeAudioDataContinuous(bufferSize)
                } else {
                    writeAudioDataToFile(bufferSize)
                }
            }

            callback?.onRecordingStarted()
            Log.i(TAG, "Recording started, continuous mode: $continuousMode")
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

        val wasContinuousMode = isContinuousMode
        isRecording = false
        isContinuousMode = false

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
        val finalRms = averageRms
        Log.i(TAG, "Recording stopped, averageRms: $finalRms")

        // In continuous mode, only return the file if there was speech in it
        val hasContent = if (wasContinuousMode) {
            hasSpeechInCurrentChunk && file != null && file.exists() && file.length() > 44
        } else {
            file != null && file.exists() && file.length() > 44
        }

        if (hasContent && file != null) {
            // Write WAV header
            writeWavHeader(file)
            Log.i(TAG, "Recording stopped, file: ${file.absolutePath}, size: ${file.length()}, rms: $finalRms")
            callback?.onRecordingStopped(file, finalRms)
            return file
        } else {
            Log.w(TAG, "Recording file is empty or doesn't exist (or no speech in continuous mode)")
            // Clean up the file if it exists but has no speech
            file?.delete()
            callback?.onRecordingStopped(null, finalRms)
            return null
        }
    }

    fun cancelRecording() {
        if (!isRecording) return

        isRecording = false
        isContinuousMode = false

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
        resetSpeechState()
        callback?.onRecordingStopped(null, 0.0)
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
                        // Calculate RMS for volume detection
                        // PCM 16-bit: 2 bytes per sample, little-endian
                        for (i in 0 until read - 1 step 2) {
                            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                            // Convert to signed 16-bit
                            val signedSample = if (sample > 32767) sample - 65536 else sample
                            sumSquares += signedSample.toDouble() * signedSample.toDouble()
                            sampleCount++
                        }
                    }
                }

                // Calculate final average RMS
                if (sampleCount > 0) {
                    averageRms = kotlin.math.sqrt(sumSquares / sampleCount)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data: ${e.message}")
        }
    }

    /**
     * Continuous recording mode: monitors volume and automatically chunks based on speech/silence.
     */
    private fun writeAudioDataContinuous(bufferSize: Int) {
        val data = ByteArray(bufferSize)
        var file = outputFile ?: return
        var fos: FileOutputStream? = null

        try {
            fos = FileOutputStream(file)
            // Write placeholder WAV header (44 bytes)
            fos.write(ByteArray(44))

            while (isRecording && isContinuousMode) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: break
                if (read > 0) {
                    // Calculate RMS for this buffer
                    currentRms = calculateRms(data, read)
                    val currentTime = System.currentTimeMillis()

                    // Detect speech/silence transitions
                    if (currentRms >= SPEECH_THRESHOLD_RMS) {
                        // Speech detected
                        if (!isSpeaking) {
                            isSpeaking = true
                            speechStartTime = currentTime
                            silenceStartTime = 0L
                            Log.i(TAG, "Speech started, RMS: $currentRms")
                            callback?.onSpeechDetected()
                        }
                        hasSpeechInCurrentChunk = true
                    } else if (currentRms < SILENCE_THRESHOLD_RMS) {
                        // Silence detected
                        if (isSpeaking) {
                            isSpeaking = false
                            silenceStartTime = currentTime
                            Log.i(TAG, "Silence started, RMS: $currentRms")
                            callback?.onSilenceDetected()
                        }
                    }

                    // Write audio data to current chunk
                    fos.write(data, 0, read)

                    // Check if we should finalize this chunk
                    // Conditions: had speech for >= 3 seconds, then silence for >= 3 seconds
                    val speechDuration = if (hasSpeechInCurrentChunk && speechStartTime > 0) {
                        currentTime - speechStartTime
                    } else 0L

                    val silenceDuration = if (silenceStartTime > 0) {
                        currentTime - silenceStartTime
                    } else 0L

                    if (hasSpeechInCurrentChunk &&
                        speechDuration >= MIN_SPEECH_DURATION_MS &&
                        silenceDuration >= SILENCE_DURATION_FOR_CHUNK_MS) {

                        Log.i(TAG, "Chunk ready! Speech duration: ${speechDuration}ms, Silence duration: ${silenceDuration}ms")

                        // Finalize current chunk
                        fos.close()
                        writeWavHeader(file)

                        // Notify that chunk is ready for transcription
                        val completedChunk = file
                        callback?.onChunkReady(completedChunk)

                        // Start a new chunk
                        chunkCounter++
                        file = File.createTempFile("voice_chunk_${chunkCounter}_", ".wav", context.cacheDir)
                        outputFile = file
                        fos = FileOutputStream(file)
                        fos.write(ByteArray(44)) // WAV header placeholder

                        // Reset speech state for new chunk
                        resetSpeechState()

                        Log.i(TAG, "Started new chunk: ${file.name}")
                    }
                }
            }

            // Close the final file output stream
            fos?.close()

        } catch (e: Exception) {
            Log.e(TAG, "Error in continuous recording: ${e.message}")
            fos?.close()
        }
    }

    /**
     * Calculate RMS (Root Mean Square) amplitude from PCM 16-bit audio data.
     */
    private fun calculateRms(data: ByteArray, length: Int): Double {
        var sumSquares = 0.0
        var sampleCount = 0

        // PCM 16-bit: 2 bytes per sample, little-endian
        for (i in 0 until length - 1 step 2) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            // Convert to signed 16-bit
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sumSquares += signedSample.toDouble() * signedSample.toDouble()
            sampleCount++
        }

        return if (sampleCount > 0) {
            kotlin.math.sqrt(sumSquares / sampleCount)
        } else {
            0.0
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
