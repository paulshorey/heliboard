package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class TranscriptPostProcessorTest {

    private fun process(input: String): String =
        TranscriptPostProcessor.processCurrentParagraph(input) ?: input

    // --- Exclamation point ---

    @Test
    fun `replaces dot-space exclamation point dot with bang`() {
        assertEquals(
            "That was great!",
            process("That was great. Exclamation point.")
        )
    }

    @Test
    fun `replaces question mark before exclamation point`() {
        assertEquals(
            "Really!",
            process("Really? Exclamation point.")
        )
    }

    @Test
    fun `replaces bang before exclamation point`() {
        assertEquals(
            "Wow!",
            process("Wow! Exclamation point.")
        )
    }

    @Test
    fun `replaces trailing exclamation point-bang variant`() {
        assertEquals(
            "Nice!",
            process("Nice. Exclamation point!")
        )
    }

    @Test
    fun `replaces exclamation mark alias`() {
        assertEquals(
            "Wow!",
            process("Wow. Exclamation mark.")
        )
    }

    @Test
    fun `standalone exclamation point at sentence end`() {
        assertEquals(
            "Hello!",
            process("Hello exclamation point.")
        )
    }

    @Test
    fun `case insensitive match`() {
        assertEquals(
            "Wow!",
            process("Wow. exclamation point.")
        )
    }

    // --- Comma ---

    @Test
    fun `replaces dot-space comma with comma`() {
        assertEquals(
            "Hello, world",
            process("Hello. comma world")
        )
    }

    @Test
    fun `replaces comma-comma dedup`() {
        assertEquals(
            "Hello, world",
            process("Hello, comma world")
        )
    }

    @Test
    fun `replaces comma-comma literal dedup`() {
        assertEquals(
            "Hello, world",
            process("Hello comma, world")
        )
    }

    // --- Question mark ---

    @Test
    fun `replaces dot-space question mark-question`() {
        assertEquals(
            "Really?",
            process("Really. question mark?")
        )
    }

    @Test
    fun `replaces dot-space question mark-dot`() {
        assertEquals(
            "Really?",
            process("Really. Question mark.")
        )
    }

    @Test
    fun `standalone question mark at end`() {
        assertEquals(
            "Are you sure?",
            process("Are you sure question mark.")
        )
    }

    // --- Period / full stop ---

    @Test
    fun `replaces question-space period-dot`() {
        assertEquals(
            "End.",
            process("End? period.")
        )
    }

    @Test
    fun `replaces full stop alias`() {
        assertEquals(
            "End.",
            process("End! full stop.")
        )
    }

    @Test
    fun `standalone period dot`() {
        assertEquals(
            "End.",
            process("End period.")
        )
    }

    // --- Colon ---

    @Test
    fun `replaces dot-space colon-colon`() {
        assertEquals(
            "Note:",
            process("Note. colon:")
        )
    }

    @Test
    fun `standalone colon-dot`() {
        assertEquals(
            "Dear sir:",
            process("Dear sir colon.")
        )
    }

    // --- Semicolon ---

    @Test
    fun `replaces dot-space semicolon-semicolon`() {
        assertEquals(
            "Also;",
            process("Also. semicolon;")
        )
    }

    @Test
    fun `standalone semicolon-dot`() {
        assertEquals(
            "However;",
            process("However semicolon.")
        )
    }

    // --- No match → null ---

    @Test
    fun `returns null when no rules match`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph("Hello world this is normal text")
        )
    }

    // --- Multiple replacements in one paragraph ---

    @Test
    fun `handles multiple spelled-out punctuation in one paragraph`() {
        assertEquals(
            "Hello, are you sure? Yes!",
            process("Hello, comma are you sure question mark. Yes exclamation point.")
        )
    }

    // --- Mid-sentence should still replace when pattern matches ---

    @Test
    fun `exclamation point without trailing dot is untouched`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "An exclamation point is a type of punctuation"
            )
        )
    }

    // --- Rules list sanity ---

    @Test
    fun `rules are sorted longest first`() {
        val lengths = TranscriptPostProcessor.rules.map { it.find.length }
        assertEquals(lengths, lengths.sortedDescending())
    }
}
