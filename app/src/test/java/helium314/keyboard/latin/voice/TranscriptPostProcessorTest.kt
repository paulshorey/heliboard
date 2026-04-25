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
    fun `exclamation point at very start of paragraph`() {
        assertEquals(
            "!",
            process("exclamation point.")
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

    @Test
    fun `comma at very start`() {
        assertEquals(
            ", world",
            process("comma, world")
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

    @Test
    fun `question mark at very start`() {
        assertEquals(
            "?",
            process("question mark.")
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

    @Test
    fun `period at very start`() {
        assertEquals(
            ".",
            process("period.")
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

    @Test
    fun `colon at very start`() {
        assertEquals(
            ":",
            process("colon.")
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

    @Test
    fun `semicolon at very start`() {
        assertEquals(
            ";",
            process("semicolon.")
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

    // --- Leading casing (sentence-start detection) ---

    private fun adjust(chunk: String, context: String): String =
        TranscriptPostProcessor.adjustLeadingCasing(chunk, context)

    @Test
    fun `casing lowercased when cursor sits after a word`() {
        assertEquals("bought some milk", adjust("Bought some milk", "I went to the store and "))
    }

    @Test
    fun `casing lowercased when cursor is directly after a letter`() {
        assertEquals("bought", adjust("Bought", "I went to the store and"))
    }

    @Test
    fun `casing preserved after period and space`() {
        assertEquals("Bought some milk", adjust("Bought some milk", "I went to the store. "))
    }

    @Test
    fun `casing preserved after exclamation and space`() {
        assertEquals("Bought some milk", adjust("Bought some milk", "Wow! "))
    }

    @Test
    fun `casing preserved after question mark and space`() {
        assertEquals("Really", adjust("Really", "Are you sure? "))
    }

    @Test
    fun `casing preserved after newline`() {
        assertEquals("Hello", adjust("Hello", "first line\n"))
    }

    @Test
    fun `casing preserved when context is empty`() {
        assertEquals("Hello world", adjust("Hello world", ""))
    }

    @Test
    fun `casing preserved when context is whitespace only`() {
        assertEquals("Hello world", adjust("Hello world", "   \n  "))
    }

    @Test
    fun `casing preserved after period with closing quote`() {
        assertEquals("Then he left", adjust("Then he left", "\"That was weird.\" "))
    }

    @Test
    fun `casing preserved after period with closing parenthesis`() {
        assertEquals("The next day", adjust("The next day", "(see footnote.) "))
    }

    @Test
    fun `casing preserved for standalone pronoun I`() {
        assertEquals("I went home", adjust("I went home", "so "))
    }

    @Test
    fun `casing preserved for I'm contraction`() {
        assertEquals("I'm going", adjust("I'm going", "so "))
    }

    @Test
    fun `casing preserved for I'll contraction`() {
        assertEquals("I'll be there", adjust("I'll be there", "so "))
    }

    @Test
    fun `casing preserved for curly apostrophe I contraction`() {
        assertEquals("I’ve tried", adjust("I’ve tried", "so "))
    }

    @Test
    fun `casing preserved for acronyms`() {
        assertEquals("NASA launched", adjust("NASA launched", "yesterday "))
    }

    @Test
    fun `casing preserved for camelCase first word`() {
        assertEquals("iPhone updates", adjust("iPhone updates", "the "))
    }

    @Test
    fun `casing preserved for Pascal case with internal caps`() {
        assertEquals("McDonald's opened", adjust("McDonald's opened", "then "))
    }

    @Test
    fun `casing preserved for non-letter first char`() {
        assertEquals("42 things", adjust("42 things", "and "))
    }

    @Test
    fun `casing preserved when already lowercase`() {
        assertEquals("bought milk", adjust("bought milk", "and "))
    }

    @Test
    fun `casing preserved for empty chunk`() {
        assertEquals("", adjust("", "and "))
    }

    @Test
    fun `casing preserved after comma (treated as mid-sentence)`() {
        // Comma ends a clause but not a sentence — lowercase is correct.
        assertEquals("then we went", adjust("Then we went", "First we ate, "))
    }

    @Test
    fun `casing preserved after semicolon (treated as mid-sentence)`() {
        assertEquals("however", adjust("However", "I was tired; "))
    }

    @Test
    fun `casing lowercased with multi-word chunk mid-sentence`() {
        assertEquals(
            "bought some milk and eggs",
            adjust("Bought some milk and eggs", "I went to the store and ")
        )
    }

    @Test
    fun `casing preserved at document start with no context`() {
        assertEquals("The quick brown fox", adjust("The quick brown fox", ""))
    }

    // --- Trailing punctuation stripping (mid-sentence insertion) ---

    private fun strip(chunk: String, after: String): String =
        TranscriptPostProcessor.stripTrailingPunctuationIfMidSentence(chunk, after)

    @Test
    fun `strips trailing period when followed by lowercase word`() {
        assertEquals("some extra words", strip("some extra words.", "and more text"))
    }

    @Test
    fun `strips trailing period when followed by space then lowercase`() {
        assertEquals("some extra words", strip("some extra words.", " and more text"))
    }

    @Test
    fun `strips trailing exclamation when followed by lowercase`() {
        assertEquals("more words", strip("more words!", "continue here"))
    }

    @Test
    fun `strips trailing question mark when followed by lowercase`() {
        assertEquals("some text", strip("some text?", "follows on"))
    }

    @Test
    fun `preserves trailing period when followed by uppercase (new sentence)`() {
        assertEquals("first sentence.", strip("first sentence.", "Second sentence."))
    }

    @Test
    fun `preserves trailing period when nothing follows (end of text)`() {
        assertEquals("last sentence.", strip("last sentence.", ""))
    }

    @Test
    fun `preserves trailing period when followed only by whitespace`() {
        assertEquals("last sentence.", strip("last sentence.", "   "))
    }

    @Test
    fun `preserves trailing period when followed by newline`() {
        assertEquals("end of paragraph.", strip("end of paragraph.", "\nnew paragraph"))
    }

    @Test
    fun `preserves trailing comma (not sentence-ending punctuation)`() {
        assertEquals("word,", strip("word,", "continues here"))
    }

    @Test
    fun `preserves trailing colon`() {
        assertEquals("items:", strip("items:", "first item"))
    }

    @Test
    fun `handles empty chunk`() {
        assertEquals("", strip("", "anything"))
    }

    @Test
    fun `strips period when followed by digit (not a letter so preserves)`() {
        // Digit is not a lowercase letter — do not strip
        assertEquals("some text.", strip("some text.", "42 more"))
    }

    // --- Short-pause period softening ---

    private fun soften(
        previous: String,
        current: String = "This continues",
        pauseSeconds: Double
    ): TranscriptPostProcessor.ShortPauseRewriteResult? =
        TranscriptPostProcessor.rewriteShortPauseBoundary(previous, current, pauseSeconds)

    @Test
    fun `rewrites trailing period to comma after short pause`() {
        val result = soften("This is still.", pauseSeconds = 0.7)
        assertEquals("This is still,", result?.previousText)
        assertEquals("this continues", result?.currentText)
    }

    @Test
    fun `preserves trailing period after long pause`() {
        assertNull(soften("This is done.", pauseSeconds = 1.6))
    }

    @Test
    fun `preserves trailing question mark after short pause`() {
        assertNull(soften("Are you sure?", pauseSeconds = 0.6))
    }

    @Test
    fun `preserves abbreviation periods after short pause`() {
        assertNull(soften("I met Dr.", pauseSeconds = 0.6))
    }

    @Test
    fun `rewrites period before closing quote`() {
        val result = soften("He said \"this works.\"", pauseSeconds = 0.6)
        assertEquals("He said \"this works,\"", result?.previousText)
        assertEquals("this continues", result?.currentText)
    }
}
