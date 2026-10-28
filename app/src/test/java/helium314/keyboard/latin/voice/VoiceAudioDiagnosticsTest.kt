// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class VoiceAudioDiagnosticsTest {
    private fun pcm(vararg values: Int): ByteArray = values.flatMap {
        listOf(it.toByte(), (it shr 8).toByte())
    }.toByteArray()

    @Test fun measuresSignedPcmIncludingFullScaleWithoutOverflowOrMutation() {
        val data = pcm(0, -32768, 32767, -100, 100)
        val original = data.copyOf()
        val level = VoiceAudioDiagnostics.measure(data)
        assertEquals(5, level.samples)
        assertEquals(32768, level.peak)
        assertEquals(1, level.zeroSamples)
        assertEquals(2, level.clippedSamples)
        assertEquals(sqrt((32768.0 * 32768 + 32767.0 * 32767 + 20000) / 5), level.rms, 0.0001)
        assertTrue(original.contentEquals(data))
        assertEquals(0.0, VoiceAudioDiagnostics.measure(byteArrayOf()).rms)
    }

    @Test fun quietAndZeroPcmAreMeasuredBelowTheEffectiveThresholdAndTailIsFlushed() {
        val diagnostics = VoiceAudioDiagnostics()
        val quiet = VoiceAudioDiagnostics.measure(pcm(-80, 80))
        assertFalse(diagnostics.add(quiet, 120.0, 260.0, 380.0, 100.0))
        diagnostics.add(VoiceAudioDiagnostics.measure(pcm(0, 0)), 120.0, 260.0, 380.0, 80.0)
        val line = diagnostics.takeSummary()!!
        assertTrue(line.contains("chunkRms=[80, 0]"))
        assertTrue(line.contains("speechEvidenceChunks=0 belowSilenceChunks=2"))
        assertTrue(line.contains("silenceThreshold=260..260 onsetThreshold=380..380"))
        assertTrue(line.contains("zeroSamples=2"))
        assertTrue(line.contains("pcmPolicy=forward_all"))
        assertNull(diagnostics.takeSummary())
    }

    @Test fun legacyCaptureRateAndRateChangesPreserveRealTimeOffsetsAndRawLevels() {
        val diagnostics = VoiceAudioDiagnostics()
        val level = VoiceAudioDiagnostics.measure(pcm(*IntArray(800) { 50 }))
        diagnostics.add(level, 120.0, 260.0, 380.0, 50.0, 8000)
        val first = diagnostics.takeSummary()!!
        assertTrue(first.contains("pcmMs=0..100"))
        assertTrue(first.contains("captureRate=8000"))
        assertTrue(first.contains("rms=50 peak=50"))
        diagnostics.add(level, 120.0, 260.0, 380.0, 50.0, 16000)
        assertTrue(diagnostics.takeSummary()!!.contains("pcmMs=100..150"))
    }

    @Test fun boundsEachWindowAndPreservesCaptureOffsetsAfterReset() {
        val diagnostics = VoiceAudioDiagnostics()
        val level = VoiceAudioDiagnostics.measure(pcm(*IntArray(1600) { 1000 }))
        repeat(20) { index ->
            assertEquals(index == 19, diagnostics.add(level, 40.0, 180.0, 300.0, 1000.0))
        }
        val line = diagnostics.takeSummary()!!
        assertTrue(line.contains("pcmMs=0..2000 chunks=20"))
        assertTrue(line.contains("speechEvidenceChunks=20 belowSilenceChunks=0"))
        assertTrue(line.contains("rms=1000 peak=1000"))
        diagnostics.add(level, 50.0, 190.0, 310.0, 1000.0)
        val tail = diagnostics.takeSummary()!!
        assertTrue(tail.contains("pcmMs=2000..2100 chunks=1"))
        assertTrue(tail.contains("noiseFloor=50..50 silenceThreshold=190..190 onsetThreshold=310..310"))
    }
}
