// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoicePostTranscriptionFilterTest {
    @Test
    fun `null becomes empty string`() {
        assertEquals("", VoicePostTranscriptionFilter.applyPostTranscriptionFilter(null))
    }

    @Test
    fun `text passes through unchanged`() {
        val s = "Hello there. Thanks!"
        assertEquals(s, VoicePostTranscriptionFilter.applyPostTranscriptionFilter(s))
    }
}
