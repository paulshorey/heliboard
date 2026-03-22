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

    @Test
    fun `converts single digit words`() {
        assertEquals("5", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("five"))
        assertEquals("0", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("zero"))
    }

    @Test
    fun `converts teen numbers`() {
        assertEquals("13", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("thirteen"))
        assertEquals("19", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("nineteen"))
    }

    @Test
    fun `converts compound numbers as whole unit`() {
        assertEquals("32", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("thirty two"))
        assertEquals("21", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("twenty one"))
        assertEquals("99", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("ninety nine"))
    }

    @Test
    fun `leaves hundred as word`() {
        assertEquals(
            "7 hundred 32",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("seven hundred thirty two")
        )
    }

    @Test
    fun `leaves million as word`() {
        assertEquals(
            "21 million",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("twenty one million")
        )
    }

    @Test
    fun `leaves hundred and thousand as words`() {
        assertEquals(
            "2 hundred 30 thousand",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("two hundred thirty thousand")
        )
    }

    @Test
    fun `preserves and between hundred and tens`() {
        assertEquals(
            "2 hundred and 30 thousand",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("two hundred and thirty thousand")
        )
    }

    @Test
    fun `numbers mixed with regular words`() {
        assertEquals(
            "I have 3 cats and 12 dogs",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("I have three cats and twelve dogs")
        )
    }

    @Test
    fun `number conversion is case insensitive`() {
        assertEquals("42", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("Forty Two"))
    }

    @Test
    fun `collapses spaces between consecutive non alphabetic characters`() {
        assertEquals("(7/7)", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("(7/ 7 )"))
    }

    @Test
    fun `replaces dash hyphen and minus during cleanup`() {
        assertEquals("word-word", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("word hyphen word"))
        assertEquals("word - word", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("word dash word"))
        assertEquals("8-3", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("eight minus three"))
        assertEquals("8-3", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("eight minus sign three"))
    }

    @Test
    fun `replaces simple cleanup edge cases`() {
        assertEquals("zero in", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("zero in"))
        assertEquals("100", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("one hundred"))
        assertEquals("1000", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("one thousand"))
        assertEquals("1000000", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("one million"))
        assertEquals("1000000000", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("one billion"))
        assertEquals("-5", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("negative five"))
    }

    @Test
    fun `turns remaining one before words back into word one`() {
        assertEquals("one apple", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("one apple"))
    }
}
