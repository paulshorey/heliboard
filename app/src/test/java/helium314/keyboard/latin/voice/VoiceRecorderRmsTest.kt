// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

class VoiceRecorderRmsTest {
    private fun pcm(vararg values: Int) = values.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test fun rmsPreservesSignedFullScaleAndQuietSamplesWithoutChangingPcm() {
        val input = pcm(0, -32768, 32767, -100, 100)
        val original = input.copyOf()
        assertEquals(sqrt((32768.0 * 32768 + 32767.0 * 32767 + 20000) / 5), VoiceRecorder.rms(input), 0.0001)
        assertContentEquals(original, input)
        assertEquals(80.0, VoiceRecorder.rms(pcm(-80, 80)))
        assertEquals(0.0, VoiceRecorder.rms(byteArrayOf()))
    }
}
