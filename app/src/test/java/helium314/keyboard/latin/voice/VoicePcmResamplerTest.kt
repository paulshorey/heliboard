// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

class VoicePcmResamplerTest {
    private fun pcm(vararg values: Int) = values.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test fun interpolationPreservesQuietDcOriginalSamplesRailsAndDuration() {
        val resampler = VoicePcmResampler()
        assertContentEquals(pcm(50, 50, 50, 50), resampler.upsample8kTo16k(pcm(50, 50)))
        val source = pcm(-32768, 32767, 0)
        val original = source.copyOf()
        assertContentEquals(pcm(-16359, -32768, 0, 32767, 16383, 0), resampler.upsample8kTo16k(source))
        assertContentEquals(original, source)
        assertEquals(3200, VoicePcmResampler().upsample8kTo16k(ByteArray(1600)).size)
    }

    @Test fun chunkBoundariesGiveTheSameWaveformAsOneContinuousRead() {
        val continuous = VoicePcmResampler().upsample8kTo16k(pcm(100, -300, 900, -1000))
        val chunked = VoicePcmResampler()
        val split = chunked.upsample8kTo16k(pcm(100, -300)) + chunked.upsample8kTo16k(byteArrayOf()) +
            chunked.upsample8kTo16k(pcm(900, -1000))
        assertContentEquals(continuous, split)
        assertContentEquals(pcm(-1000, -1000), VoicePcmResampler().upsample8kTo16k(pcm(-1000)))
    }
}
