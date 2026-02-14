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
import kotlin.math.sqrt

/**
 * Records audio from the device microphone with client-side silence detection.
 *
 * Starts recording instantly on the device (no network dependency).
 * Streams raw PCM chunks via [RecordingCallback.onAudioChunk] and emits speech
 * boundary callbacks ([RecordingCallback.onSpeechStarted] / [RecordingCallback.onSpeechStopped])
 * based on adaptive silence detection.
 *
 * Audio format: PCM 16-bit, 16kHz, mono — compatible with Deepgram and most speech APIs.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"

        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        private const val BYTES_PER_SAMPLE = 2

        /** How often we read from the mic and evaluate silence (ms). */
        private const val READ_INTERVAL_MS = 100L

        /** Bytes per read: 16000 samples/s * 2 bytes * 0.1s = 3200 bytes. */
        private const val BYTES_PER_READ = (SAMPLE_RATE * BYTES_PER_SAMPLE * READ_INTERVAL_MS / 1000).toInt()

        /**
         * Adaptive silence detection:
         * - We keep a rolling noise floor estimate
         * - Speech/silence thresholds are derived from that floor with margins
         * This is more robust than a single fixed threshold across environments.
         */
        private const val INITIAL_NOISE_FLOOR = 120.0
        private const val DEFAULT_SILENCE_THRESHOLD = 220.0
        private const val SPEECH_HYSTERESIS = 140.0
        private const val SPEECH_MARGIN = 260.0
        private const val SILENCE_MARGIN = 140.0
        private const val ENERGY_SMOOTHING_ALPHA = 0.2
        private const val NOISE_FLOOR_MIN = 40.0
        private const val NOISE_FLOOR_MAX = 1000.0
        private const val NOISE_FLOOR_MAX_STEP_UP = 50.0

        /** Rolling window size for percentile-based noise floor estimation (iterations).
         *  300 iterations at 100ms = 30 seconds of audio history. */
        private const val NOISE_FLOOR_WINDOW_SIZE = 300

        /** Percentile of energy distribution used as noise floor (0.0-1.0).
         *  20th percentile anchors to the quieter moments — robust against
         *  speech or transient noise pulling the estimate upward. */
        private const val NOISE_FLOOR_PERCENTILE = 0.20

        /** How often to recalculate the percentile-based noise floor (iterations).
         *  10 iterations at 100ms = every 1 second. */
        private const val NOISE_FLOOR_RECALC_INTERVAL = 10

        /** Default silence duration (ms) before declaring speech stopped. */
        private const val DEFAULT_SILENCE_DURATION_MS = 1000L
        private const val MIN_SILENCE_DURATION_MS = 1000L
        private const val MAX_SILENCE_DURATION_MS = 30_000L
        private const val MIN_ALLOWED_SILENCE_THRESHOLD = 40.0
        private const val MAX_ALLOWED_SILENCE_THRESHOLD = 5000.0
    }

    /**
     * Callback for recording lifecycle and live speech detection.
     */
    interface RecordingCallback {
        /** Recording started successfully — microphone is live. */
        fun onRecordingStarted()

        /**
         * Raw PCM16 microphone chunk from the live stream.
         * Chunk cadence is roughly every [READ_INTERVAL_MS] while recording.
         */
        fun onAudioChunk(pcmData: ByteArray)

        /** Called when speech is detected (silence ended). */
        fun onSpeechStarted()

        /** Called when silence is detected after speech. */
        fun onSpeechStopped()

        /** Recording stopped (either explicitly or due to error). */
        fun onRecordingStopped()

        /** An error occurred during recording. */
        fun onRecordingError(error: String)
    }

    private data class SilenceConfig(
        val silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS,
        val silenceThreshold: Double = DEFAULT_SILENCE_THRESHOLD
    )

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    @Volatile private var silenceConfig = SilenceConfig()
    private var callback: RecordingCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isCurrentlyRecording: Boolean get() = isRecording
    val isCurrentlyPaused: Boolean get() = isPaused

    fun setCallback(callback: RecordingCallback?) {
        this.callback = callback
    }

    /**
     * Update silence detection configuration for subsequent recording sessions.
     */
    fun updateSilenceConfig(silenceDurationMs: Long, silenceThreshold: Double) {
        val sanitizedDuration = silenceDurationMs.coerceIn(MIN_SILENCE_DURATION_MS, MAX_SILENCE_DURATION_MS)
        val sanitizedThreshold = silenceThreshold.coerceIn(
            MIN_ALLOWED_SILENCE_THRESHOLD,
            MAX_ALLOWED_SILENCE_THRESHOLD
        )
        silenceConfig = SilenceConfig(
            silenceDurationMs = sanitizedDuration,
            silenceThreshold = sanitizedThreshold
        )
        Log.i(
            TAG,
            "Silence config updated: duration=${sanitizedDuration}ms, threshold=${sanitizedThreshold}"
        )
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording immediately. Returns true if the microphone started.
     * This is purely local — no network calls, no latency.
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

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBuf")
            callback?.onRecordingError("Failed to initialize audio recording")
            return false
        }

        try {
            val bufferSize = maxOf(minBuf * 2, BYTES_PER_READ * 4)
            audioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                callback?.onRecordingError("Failed to initialize audio recording")
                releaseRecorder()
                return false
            }

            isPaused = false
            audioRecord?.startRecording()
            isRecording = true

            recordingThread = thread(start = true, name = "VoiceRecorder") {
                recordingLoop()
            }

            val callbackSnapshot = callback
            mainHandler.post { callbackSnapshot?.onRecordingStarted() }
            val configSnapshot = silenceConfig
            Log.i(
                TAG,
                "VOICE_STEP_1 recording started (${SAMPLE_RATE}Hz), " +
                    "silenceDuration=${configSnapshot.silenceDurationMs}ms, " +
                    "silenceThreshold=${configSnapshot.silenceThreshold}"
            )
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
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

    /** Stop recording. */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            recordingThread?.join(2000)
        } catch (_: InterruptedException) {}
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        releaseRecorder()
        Log.i(TAG, "Recording stopped")
        val callbackSnapshot = callback
        mainHandler.post { callbackSnapshot?.onRecordingStopped() }
    }

    fun cancelRecording() = stopRecording()

    fun pauseRecording() {
        if (isRecording && !isPaused) {
            isPaused = true
            Log.i(TAG, "Recording paused")
        }
    }

    fun resumeRecording() {
        if (isRecording && isPaused) {
            isPaused = false
            Log.i(TAG, "Recording resumed")
        }
    }

    private fun releaseRecorder() {
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        recordingThread = null
    }

    // ── Recording loop with silence detection ──────────────────────────

    private fun recordingLoop() {
        val readBuffer = ByteArray(BYTES_PER_READ)
        var silenceDurationMs = 0L
        var isSpeaking = false
        var noiseFloor = INITIAL_NOISE_FLOOR
        var smoothedEnergy = INITIAL_NOISE_FLOOR
        val energyHistory = ArrayDeque<Double>()
        var noiseFloorRecalcCounter = 0
        val configSnapshot = silenceConfig
        val minSilenceThreshold = configSnapshot.silenceThreshold
        val minSpeechThreshold = minSilenceThreshold + SPEECH_HYSTERESIS

        try {
            while (isRecording) {
                val bytesRead = audioRecord?.read(readBuffer, 0, BYTES_PER_READ) ?: break
                if (bytesRead <= 0) continue

                // When paused, discard audio but keep the loop alive
                if (isPaused) {
                    silenceDurationMs = 0L
                    isSpeaking = false
                    continue
                }

                val chunk = if (bytesRead == BYTES_PER_READ) readBuffer.copyOf()
                            else readBuffer.copyOf(bytesRead)

                // Always forward the live PCM stream; streaming transcription consumes
                // this path instead of relying on locally cut WAV segments.
                val callbackSnapshot = callback
                mainHandler.post { callbackSnapshot?.onAudioChunk(chunk) }

                val energy = rmsEnergy(chunk)
                smoothedEnergy = (ENERGY_SMOOTHING_ALPHA * energy) +
                    ((1.0 - ENERGY_SMOOTHING_ALPHA) * smoothedEnergy)

                val speechThreshold = maxOf(minSpeechThreshold, noiseFloor + SPEECH_MARGIN)
                val silenceThreshold = maxOf(minSilenceThreshold, noiseFloor + SILENCE_MARGIN)
                val chunkMs = (bytesRead.toLong() * 1000) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                val hasSpeech = if (isSpeaking) {
                    smoothedEnergy >= silenceThreshold
                } else {
                    smoothedEnergy >= speechThreshold
                }

                // Percentile-based noise floor: track raw RMS energy (not EMA-smoothed)
                // to avoid speech->silence lag contaminating the low-percentile baseline.
                energyHistory.addLast(energy)
                if (energyHistory.size > NOISE_FLOOR_WINDOW_SIZE) {
                    energyHistory.removeFirst()
                }
                noiseFloorRecalcCounter++
                if (noiseFloorRecalcCounter >= NOISE_FLOOR_RECALC_INTERVAL && energyHistory.size >= 20) {
                    noiseFloorRecalcCounter = 0
                    val sorted = energyHistory.toList().sorted()
                    val idx = (sorted.size * NOISE_FLOOR_PERCENTILE).toInt()
                        .coerceIn(0, sorted.size - 1)
                    val targetNoiseFloor = sorted[idx].coerceIn(NOISE_FLOOR_MIN, NOISE_FLOOR_MAX)
                    noiseFloor = if (targetNoiseFloor > noiseFloor) {
                        minOf(targetNoiseFloor, noiseFloor + NOISE_FLOOR_MAX_STEP_UP)
                    } else {
                        // Let the floor decrease quickly when ambient gets quieter.
                        targetNoiseFloor
                    }
                }

                if (hasSpeech) {
                    if (!isSpeaking) {
                        isSpeaking = true
                        val callbackSnapshot = callback
                        mainHandler.post { callbackSnapshot?.onSpeechStarted() }
                    }
                    silenceDurationMs = 0L
                } else {
                    if (isSpeaking) {
                        silenceDurationMs += chunkMs
                        if (silenceDurationMs >= configSnapshot.silenceDurationMs) {
                            isSpeaking = false
                            silenceDurationMs = 0L
                            Log.i(
                                TAG,
                                "VOICE_STEP_2 silence detected (${configSnapshot.silenceDurationMs}ms window), " +
                                    "energy=${smoothedEnergy.toInt()}, threshold=${silenceThreshold.toInt()} — speech stopped"
                            )
                            val callbackSnapshot = callback
                            mainHandler.post { callbackSnapshot?.onSpeechStopped() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in recording loop: ${e.message}")
            val callbackSnapshot = callback
            mainHandler.post { callbackSnapshot?.onRecordingError("Recording error: ${e.message}") }
        }
    }

    // ── Utility functions ──────────────────────────────────────────────

    /** Calculate RMS energy of PCM16 little-endian audio. */
    private fun rmsEnergy(data: ByteArray): Double {
        if (data.size < 2) return 0.0
        var sum = 0.0
        val samples = data.size / 2
        for (i in 0 until samples) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo  // signed 16-bit little-endian
            sum += (sample * sample).toDouble()
        }
        return sqrt(sum / samples)
    }
}
