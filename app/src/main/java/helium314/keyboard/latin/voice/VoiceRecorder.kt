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
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records audio from the device microphone with client-side silence detection.
 *
 * Starts recording instantly on the device (no network dependency).
 * Accumulates audio into chunks, splitting on detected silence pauses.
 * When a pause is detected (silence exceeding the configured silence duration),
 * the accumulated audio segment is delivered via [RecordingCallback.onSegmentReady].
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

        /** Default silence duration (ms) before splitting a segment. */
        private const val DEFAULT_SILENCE_DURATION_MS = 1000L
        private const val MIN_SILENCE_DURATION_MS = 1000L
        private const val MAX_SILENCE_DURATION_MS = 30_000L
        private const val MIN_ALLOWED_SILENCE_THRESHOLD = 40.0
        private const val MAX_ALLOWED_SILENCE_THRESHOLD = 5000.0

        /** Minimum segment length (ms) to emit — avoids sending tiny noise blips. */
        private const val MIN_SEGMENT_MS = 500L

        /** Fraction of detected silence to trim from the end of a segment (0.0-1.0).
         *  0.5 = cut at the midpoint of the silence period. */
        private const val SILENCE_TRIM_FRACTION = 0.5

        /** Pre-speech lookback buffer duration (ms) — captures speech onset missed by energy smoothing. */
        private const val PRE_SPEECH_BUFFER_MS = 300L

        /** Minimum speech required for the FIRST segment after recording starts (ms).
         *  Lower threshold allows single-word utterances right after pressing the mic. */
        private const val MIN_SPEECH_FIRST_SEGMENT_MS = 300L

        /** Minimum speech required for subsequent segments (ms).
         *  Higher threshold filters background noise (dog barks, coughs, etc.)
         *  that accumulates less than 1 second of detected speech energy. */
        private const val MIN_SPEECH_ONGOING_MS = 1000L

        /** Maximum segment length (ms) — force-split very long speech. */
        private const val MAX_SEGMENT_MS = 60_000L
    }

    /**
     * Callback for recording lifecycle and completed audio segments.
     */
    interface RecordingCallback {
        /** Recording started successfully — microphone is live. */
        fun onRecordingStarted()

        /**
         * A complete audio segment is ready for transcription.
         * [wavData] is a self-contained WAV file (header + PCM data).
         */
        fun onSegmentReady(wavData: ByteArray)

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
    @Volatile private var forceFlushRequested = false
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
            forceFlushRequested = false
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

    /** Stop recording and emit any remaining audio as a final segment. */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        forceFlushRequested = false
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

    /**
     * Ask the recorder loop to flush the currently buffered segment.
     * This is a best-effort request used as a watchdog fallback if silence
     * detection misses a boundary in noisy environments.
     */
    fun requestSegmentFlush() {
        if (isRecording && !isPaused) {
            forceFlushRequested = true
            Log.i(TAG, "Chunk flush requested by watchdog")
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
        val segmentBuffer = ByteArrayOutputStream()
        var silenceDurationMs = 0L
        var segmentDurationMs = 0L
        var speechDurationMs = 0L   // only counts chunks where hasSpeech was true
        var segmentsEmitted = 0     // tracks how many segments have been sent this session
        var isSpeaking = false
        var noiseFloor = INITIAL_NOISE_FLOOR
        var smoothedEnergy = INITIAL_NOISE_FLOOR
        val energyHistory = ArrayDeque<Double>()
        var noiseFloorRecalcCounter = 0
        val configSnapshot = silenceConfig
        val minSilenceThreshold = configSnapshot.silenceThreshold
        val minSpeechThreshold = minSilenceThreshold + SPEECH_HYSTERESIS

        // Lookback buffer: captures recent audio between segments so speech
        // onset (which energy smoothing may classify as silence) is not lost.
        val preSpeechBuffer = ArrayDeque<ByteArray>()
        val maxPreSpeechChunks = (PRE_SPEECH_BUFFER_MS / READ_INTERVAL_MS).toInt()
        val bytesPerMs = (SAMPLE_RATE * BYTES_PER_SAMPLE) / 1000

        try {
            while (isRecording) {
                val bytesRead = audioRecord?.read(readBuffer, 0, BYTES_PER_READ) ?: break
                if (bytesRead <= 0) continue

                // When paused, discard audio but keep the loop alive
                if (isPaused) {
                    // If there was accumulated speech, emit it before we start discarding
                    if (segmentBuffer.size() > 0) {
                        val minSpeechMs = if (segmentsEmitted == 0) MIN_SPEECH_FIRST_SEGMENT_MS else MIN_SPEECH_ONGOING_MS
                        if (speechDurationMs >= minSpeechMs) {
                            emitSegment(segmentBuffer, segmentDurationMs)
                            segmentsEmitted++
                        }
                        segmentBuffer.reset()
                        segmentDurationMs = 0L
                        speechDurationMs = 0L
                        silenceDurationMs = 0L
                    }
                    preSpeechBuffer.clear()
                    continue
                }

                val chunk = if (bytesRead == BYTES_PER_READ) readBuffer.copyOf()
                            else readBuffer.copyOf(bytesRead)

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
                    // ── Speech detected ──
                    if (!isSpeaking) {
                        isSpeaking = true
                        // Prepend lookback buffer to capture speech onset that
                        // energy smoothing may have classified as silence
                        if (segmentBuffer.size() == 0 && preSpeechBuffer.isNotEmpty()) {
                            for (buffered in preSpeechBuffer) {
                                segmentBuffer.write(buffered)
                                segmentDurationMs += (buffered.size.toLong() * 1000) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                            }
                            Log.i(TAG, "Prepended ${preSpeechBuffer.size} lookback chunks " +
                                "(${preSpeechBuffer.sumOf { it.size.toLong() } * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)}ms) to new segment")
                        }
                        preSpeechBuffer.clear()
                        val callbackSnapshot = callback
                        mainHandler.post { callbackSnapshot?.onSpeechStarted() }
                    }
                    silenceDurationMs = 0L
                    segmentBuffer.write(chunk)
                    segmentDurationMs += chunkMs
                    speechDurationMs += chunkMs
                } else {
                    // ── Silence ──
                    silenceDurationMs += chunkMs

                    if (segmentBuffer.size() > 0) {
                        // Active segment: include silence as padding
                        segmentBuffer.write(chunk)
                        segmentDurationMs += chunkMs
                    } else {
                        // Between segments: maintain lookback buffer for next speech onset
                        preSpeechBuffer.addLast(chunk)
                        while (preSpeechBuffer.size > maxPreSpeechChunks) {
                            preSpeechBuffer.removeFirst()
                        }
                    }

                    if (segmentBuffer.size() > 0 && silenceDurationMs >= configSnapshot.silenceDurationMs) {
                        if (isSpeaking) {
                            isSpeaking = false
                            val callbackSnapshot = callback
                            mainHandler.post { callbackSnapshot?.onSpeechStopped() }
                        }

                        // Retroactive trim: cut back into the silence period so the
                        // emitted segment ends cleanly near where speech actually stopped,
                        // rather than at the current (lagging) detection point.
                        val trimBackMs = 0L // disabled for testing; was: Math.round(silenceDurationMs * SILENCE_TRIM_FRACTION)
                        // Don't trim so much that the emitted segment is shorter than MIN_SEGMENT_MS
                        val maxTrimForMinSegment = maxOf(0L, segmentDurationMs - MIN_SEGMENT_MS)
                        val effectiveTrimMs = minOf(trimBackMs, maxTrimForMinSegment)
                        val trimBackBytes = (effectiveTrimMs * bytesPerMs).toInt()
                        val pcmData = segmentBuffer.toByteArray()
                        val actualTrimBytes = trimBackBytes.coerceAtMost(pcmData.size)
                        val emitEndIndex = pcmData.size - actualTrimBytes
                        val emitDurationMs = segmentDurationMs - effectiveTrimMs

                        Log.i(
                            TAG,
                            "VOICE_STEP_2 silence detected (${silenceDurationMs}ms " +
                                ">= ${configSnapshot.silenceDurationMs}ms), " +
                                "energy=${smoothedEnergy.toInt()}, threshold=${silenceThreshold.toInt()} — " +
                                "cutting chunk (trimmed ${effectiveTrimMs}ms trailing silence)"
                        )

                        // Emit the retroactively trimmed segment (only if it contains real speech)
                        val minSpeechMs = if (segmentsEmitted == 0) MIN_SPEECH_FIRST_SEGMENT_MS else MIN_SPEECH_ONGOING_MS
                        if (emitDurationMs >= MIN_SEGMENT_MS && speechDurationMs >= minSpeechMs) {
                            val emitPcm = if (actualTrimBytes > 0) pcmData.copyOfRange(0, emitEndIndex) else pcmData
                            emitSegmentPcm(emitPcm, emitDurationMs)
                            segmentsEmitted++
                        } else if (speechDurationMs < minSpeechMs) {
                            Log.i(TAG, "Segment dropped: only ${speechDurationMs}ms of speech " +
                                "(minimum ${minSpeechMs}ms required, segments emitted=$segmentsEmitted)")
                        }

                        // Seed lookback buffer with the trimmed tail so that if speech
                        // resumes quickly, the onset audio is preserved in the next segment.
                        preSpeechBuffer.clear()
                        if (actualTrimBytes > 0) {
                            val trimmedTail = pcmData.copyOfRange(emitEndIndex, pcmData.size)
                            var offset = 0
                            while (offset < trimmedTail.size) {
                                val end = minOf(offset + BYTES_PER_READ, trimmedTail.size)
                                preSpeechBuffer.addLast(trimmedTail.copyOfRange(offset, end))
                                offset = end
                            }
                            while (preSpeechBuffer.size > maxPreSpeechChunks) {
                                preSpeechBuffer.removeFirst()
                            }
                        }

                        segmentBuffer.reset()
                        segmentDurationMs = 0L
                        speechDurationMs = 0L
                        silenceDurationMs = 0L
                    }
                }

                if (forceFlushRequested) {
                    forceFlushRequested = false
                    if (segmentBuffer.size() > 0) {
                        val minSpeechMs = if (segmentsEmitted == 0) MIN_SPEECH_FIRST_SEGMENT_MS else MIN_SPEECH_ONGOING_MS
                        if (segmentDurationMs >= MIN_SEGMENT_MS && speechDurationMs >= minSpeechMs) {
                            Log.i(
                                TAG,
                                "VOICE_STEP_2 watchdog flush forcing chunk at ${segmentDurationMs}ms"
                            )
                            emitSegment(segmentBuffer, segmentDurationMs)
                            segmentsEmitted++
                        } else {
                            Log.i(
                                TAG,
                                "Watchdog flush dropped segment: duration=${segmentDurationMs}ms, " +
                                    "speech=${speechDurationMs}ms (minimum speech ${minSpeechMs}ms)"
                            )
                        }
                        // Keep recorder/manager state transitions consistent: if we were in speech,
                        // signal a stop so paragraph/auto-stop timers can recover even when
                        // silence detection missed the natural boundary.
                        val wasSpeaking = isSpeaking
                        segmentBuffer.reset()
                        segmentDurationMs = 0L
                        speechDurationMs = 0L
                        silenceDurationMs = 0L
                        preSpeechBuffer.clear()
                        isSpeaking = false
                        if (wasSpeaking) {
                            val callbackSnapshot = callback
                            mainHandler.post { callbackSnapshot?.onSpeechStopped() }
                        }
                    }
                }

                // Force-split very long segments
                if (segmentDurationMs >= MAX_SEGMENT_MS) {
                    val minSpeechMs = if (segmentsEmitted == 0) MIN_SPEECH_FIRST_SEGMENT_MS else MIN_SPEECH_ONGOING_MS
                    if (speechDurationMs >= minSpeechMs) {
                        emitSegment(segmentBuffer, segmentDurationMs)
                        segmentsEmitted++
                    } else {
                        Log.i(
                            TAG,
                            "Max-segment split dropped: duration=${segmentDurationMs}ms, " +
                                "speech=${speechDurationMs}ms (minimum speech ${minSpeechMs}ms)"
                        )
                    }
                    segmentBuffer.reset()
                    segmentDurationMs = 0L
                    speechDurationMs = 0L
                    preSpeechBuffer.clear()
                }
            }

            // ── Recording ended: emit any remaining audio ──
            val minSpeechMs = if (segmentsEmitted == 0) MIN_SPEECH_FIRST_SEGMENT_MS else MIN_SPEECH_ONGOING_MS
            if (segmentBuffer.size() > 0 && segmentDurationMs >= MIN_SEGMENT_MS
                && speechDurationMs >= minSpeechMs) {
                emitSegment(segmentBuffer, segmentDurationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in recording loop: ${e.message}")
            val callbackSnapshot = callback
            mainHandler.post { callbackSnapshot?.onRecordingError("Recording error: ${e.message}") }
        }
    }

    /** Wrap raw PCM data in a WAV container and deliver to callback. */
    private fun emitSegment(buffer: ByteArrayOutputStream, durationMs: Long) {
        val pcmData = buffer.toByteArray()
        if (pcmData.isEmpty()) return
        emitSegmentPcm(pcmData, durationMs)
    }

    /** Wrap raw PCM data in a WAV container and deliver to callback. */
    private fun emitSegmentPcm(pcmData: ByteArray, durationMs: Long) {
        if (pcmData.isEmpty()) return
        val wavData = createWav(pcmData)
        Log.i(TAG, "Segment ready: ${durationMs}ms, ${wavData.size} bytes")
        val callbackSnapshot = callback
        mainHandler.post { callbackSnapshot?.onSegmentReady(wavData) }
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

    /** Wrap raw PCM16 data in a minimal WAV file (44-byte header + data). */
    private fun createWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        val header = ByteArray(44)

        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        // fmt sub-chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)         // sub-chunk size
        writeShort(header, 20, 1)        // PCM format
        writeShort(header, 22, 1)        // mono
        writeInt(header, 24, SAMPLE_RATE)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, BYTES_PER_SAMPLE) // block align
        writeShort(header, 34, 16)       // bits per sample

        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, pcmData.size)

        return header + pcmData
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
