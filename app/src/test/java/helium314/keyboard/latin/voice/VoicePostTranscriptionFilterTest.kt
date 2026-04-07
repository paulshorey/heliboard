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
    fun `replaces numbers and symbols in a single utterance`() {
        assertEquals(
            "(7/7)",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter(
                "open parenthesis seven slash seven close parenthesis"
            )
        )
    }

    @Test
    fun `replaces dash hyphen and minus during cleanup`() {
        assertEquals("word-word", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("word hyphen word"))
        assertEquals("word-word", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("word dash word"))
        assertEquals("8-3", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("eight minus three"))
        assertEquals("8-3", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("eight minus sign three"))
    }

    @Test
    fun `collapses spaces around typed dashes between letters`() {
        assertEquals(
            "Build the notes-android APK.",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("Build the notes - Android APK.")
        )
        assertEquals(
            "a–b",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("a – b")
        )
    }

    @Test
    fun `lowercases letter words in dash compounds`() {
        assertEquals(
            " build the notes-android APK.",
            VoicePostTranscriptionFilter.prepareForInsertion(
                "Build the notes - Android APK.",
                "already typing"
            )
        )
        assertEquals(
            "foo-bar-baz",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("Foo-Bar-Baz")
        )
    }

    @Test
    fun `removes sentence period placed after letter-dash compound`() {
        assertEquals("build-apk", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("build-apk."))
        assertEquals("foo-bar", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("foo-bar."))
        assertEquals(
            "build-apk.exe",
            VoicePostTranscriptionFilter.applyPostTranscriptionFilter("build-apk.exe")
        )
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
    fun `normalizes etc edge cases`() {
        assertEquals("etc...", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("e t c..."))
        assertEquals("etc", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("etcetera"))
        assertEquals("etc. please", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("etc please"))
        assertEquals("etc. please", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("etcetera please"))
    }

    @Test
    fun `normalizes standalone y to why`() {
        assertEquals("I know why.", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("I know y."))
        assertEquals("I know why", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("I know y"))
        assertEquals("why?", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("y?"))
    }

    @Test
    fun `turns remaining one before words back into word one`() {
        assertEquals("one apple", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("one apple"))
    }

    @Test
    fun `preserves literal word space in text`() {
        assertEquals("hello space world", VoicePostTranscriptionFilter.applyPostTranscriptionFilter("hello space world"))
    }

    @Test
    fun `detects standalone newline commands`() {
        assertEquals(1, VoicePostTranscriptionFilter.getNewlineCommandCount("new line"))
        assertEquals(1, VoicePostTranscriptionFilter.getNewlineCommandCount("New Line"))
        assertEquals(1, VoicePostTranscriptionFilter.getNewlineCommandCount("line break"))
        assertEquals(2, VoicePostTranscriptionFilter.getNewlineCommandCount("new paragraph"))
        assertEquals(2, VoicePostTranscriptionFilter.getNewlineCommandCount("New Paragraph"))
        assertEquals(0, VoicePostTranscriptionFilter.getNewlineCommandCount("hello new line world"))
        assertEquals(0, VoicePostTranscriptionFilter.getNewlineCommandCount("hello"))
        assertEquals(0, VoicePostTranscriptionFilter.getNewlineCommandCount(null))
    }

    @Test
    fun `prepare for insertion prepends leading space for word-like starters mid line`() {
        assertEquals(" hello", VoicePostTranscriptionFilter.prepareForInsertion("hello", "already typing"))
        assertEquals(" hello", VoicePostTranscriptionFilter.prepareForInsertion("Hello", "already typing"))
        assertEquals(" - and another thought", VoicePostTranscriptionFilter.prepareForInsertion("dash and another thought", "already typing"))
        assertEquals(" 2024", VoicePostTranscriptionFilter.prepareForInsertion("2024", "already typing"))
        assertEquals(" (note)", VoicePostTranscriptionFilter.prepareForInsertion("(note)", "already typing"))
        assertEquals(" $5", VoicePostTranscriptionFilter.prepareForInsertion("$5", "already typing"))
        assertEquals(" #topic", VoicePostTranscriptionFilter.prepareForInsertion("#topic", "already typing"))
        assertEquals(" @name", VoicePostTranscriptionFilter.prepareForInsertion("@name", "already typing"))
        assertEquals(" \"hello", VoicePostTranscriptionFilter.prepareForInsertion("\"hello", "already typing"))
    }

    @Test
    fun `prepare for insertion does not prepend space after trailing whitespace`() {
        assertEquals("hello", VoicePostTranscriptionFilter.prepareForInsertion("hello", null))
        assertEquals("hello", VoicePostTranscriptionFilter.prepareForInsertion("hello", ""))
        assertEquals("hello", VoicePostTranscriptionFilter.prepareForInsertion("hello", " "))
        assertEquals("hello", VoicePostTranscriptionFilter.prepareForInsertion("hello", "\t"))
        assertEquals("hello", VoicePostTranscriptionFilter.prepareForInsertion("hello", "\n"))
        assertEquals("2024", VoicePostTranscriptionFilter.prepareForInsertion("2024", "\n"))
    }

    @Test
    fun `prepare for insertion does not prepend space for non word-like starters`() {
        assertEquals("\"7", VoicePostTranscriptionFilter.prepareForInsertion("\"7", "already typing"))
    }

    @Test
    fun `prepare for insertion preserves literal word space`() {
        assertEquals(" space&", VoicePostTranscriptionFilter.prepareForInsertion("space ampersand", "already typing"))
    }
}
