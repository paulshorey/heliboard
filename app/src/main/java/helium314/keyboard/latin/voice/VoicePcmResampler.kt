// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** Causal linear 2x interpolation for legacy mono PCM16 SCO input. No gain or noise gate. */
internal class VoicePcmResampler {
    private var previous: Int? = null

    // Keep the previous sample across reads; reset by constructing a new instance per capture phase.
    fun upsample8kTo16k(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0) { "Incomplete PCM16 sample" }
        val output = ByteArray(pcm.size * 2)
        for (offset in pcm.indices step 2) {
            val sample = (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xff)
            val midpoint = ((previous ?: sample) + sample) / 2
            val destination = offset * 2
            output[destination] = midpoint.toByte()
            output[destination + 1] = (midpoint shr 8).toByte()
            output[destination + 2] = sample.toByte()
            output[destination + 3] = (sample shr 8).toByte()
            previous = sample
        }
        return output
    }
}
