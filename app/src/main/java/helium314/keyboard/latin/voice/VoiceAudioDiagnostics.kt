// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Numeric capture evidence only. Never retains PCM or decides which audio to send. */
internal class VoiceAudioDiagnostics {
    internal data class Level(
        val samples: Int,
        val squareSum: Double,
        val peak: Int,
        val zeroSamples: Int,
        val clippedSamples: Int,
    ) {
        val rms: Double get() = if (samples == 0) 0.0 else sqrt(squareSum / samples)
    }

    companion object {
        fun measure(pcm: ByteArray): Level {
            var squareSum = 0.0
            var peak = 0
            var zeros = 0
            var clipped = 0
            val samples = pcm.size / 2
            for (i in 0 until samples) {
                val sample = (pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xff)
                val magnitude = abs(sample)
                squareSum += sample.toDouble() * sample
                peak = maxOf(peak, magnitude)
                if (sample == 0) zeros++
                if (magnitude >= 32760) clipped++
            }
            return Level(samples, squareSum, peak, zeros, clipped)
        }
    }

    private val rmsValues = ArrayList<Int>(20)
    private val chunkSamples = ArrayList<Int>(20)
    private var samples = 0
    private var totalSamples = 0L
    private var squareSum = 0.0
    private var peak = 0
    private var zeros = 0
    private var clipped = 0
    private var evidenceChunks = 0
    private var noiseMin = Double.POSITIVE_INFINITY
    private var noiseMax = 0.0
    private var silenceMin = Double.POSITIVE_INFINITY
    private var silenceMax = 0.0
    private var onsetMin = Double.POSITIVE_INFINITY
    private var onsetMax = 0.0
    private var lastSmoothed = 0.0

    /** At most twenty reads (normally two seconds) are retained between summaries. */
    fun add(level: Level, noiseFloor: Double, silence: Double, onset: Double, smoothed: Double): Boolean {
        check(rmsValues.size < 20)
        rmsValues.add(level.rms.toInt())
        chunkSamples.add(level.samples)
        samples += level.samples
        totalSamples += level.samples
        squareSum += level.squareSum
        peak = maxOf(peak, level.peak)
        zeros += level.zeroSamples
        clipped += level.clippedSamples
        if (level.rms >= silence) evidenceChunks++
        noiseMin = minOf(noiseMin, noiseFloor)
        noiseMax = maxOf(noiseMax, noiseFloor)
        silenceMin = minOf(silenceMin, silence)
        silenceMax = maxOf(silenceMax, silence)
        onsetMin = minOf(onsetMin, onset)
        onsetMax = maxOf(onsetMax, onset)
        lastSmoothed = smoothed
        return rmsValues.size == 20
    }

    /** Flush at the normal cadence and at pause/end so short tests retain their tail. */
    fun takeSummary(): String? {
        if (rmsValues.isEmpty()) return null
        val startMs = (totalSamples - samples) * 1000 / VoiceRecorder.SAMPLE_RATE
        val endMs = totalSamples * 1000 / VoiceRecorder.SAMPLE_RATE
        val result = "VOICE levels pcmMs=$startMs..$endMs chunks=${rmsValues.size} " +
            "chunkRms=$rmsValues chunkSamples=$chunkSamples " +
            "rms=${if (samples == 0) 0 else sqrt(squareSum / samples).toInt()} peak=$peak " +
            "zeroSamples=$zeros clippedSamples=$clipped samples=$samples " +
            "noiseFloor=${noiseMin.toInt()}..${noiseMax.toInt()} " +
            "silenceThreshold=${silenceMin.toInt()}..${silenceMax.toInt()} " +
            "onsetThreshold=${onsetMin.toInt()}..${onsetMax.toInt()} ema=${lastSmoothed.toInt()} " +
            "speechEvidenceChunks=$evidenceChunks belowSilenceChunks=${rmsValues.size - evidenceChunks} " +
            "pcmPolicy=forward_all"
        rmsValues.clear()
        chunkSamples.clear()
        samples = 0
        squareSum = 0.0
        peak = 0
        zeros = 0
        clipped = 0
        evidenceChunks = 0
        noiseMin = Double.POSITIVE_INFINITY
        noiseMax = 0.0
        silenceMin = Double.POSITIVE_INFINITY
        silenceMax = 0.0
        onsetMin = Double.POSITIVE_INFINITY
        onsetMax = 0.0
        return result
    }
}
