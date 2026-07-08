package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class TranscriptPostProcessorTest {

    private fun process(input: String): String =
        TranscriptPostProcessor.processCurrentParagraph(input) ?: input

    // --- Exclamation point (capitalized command form) ---

    @Test
    fun `replaces Exclamation point after period`() {
        assertEquals(
            "That was great!",
            process("That was great. Exclamation point.")
        )
    }

    @Test
    fun `replaces Exclamation point after question mark`() {
        assertEquals(
            "Really!",
            process("Really? Exclamation point.")
        )
    }

    @Test
    fun `replaces Exclamation point after bang`() {
        assertEquals(
            "Wow!",
            process("Wow! Exclamation point.")
        )
    }

    @Test
    fun `replaces Exclamation point with bang trailing variant`() {
        assertEquals(
            "Nice!",
            process("Nice. Exclamation point!")
        )
    }

    @Test
    fun `replaces Exclamation mark alias`() {
        assertEquals(
            "Wow!",
            process("Wow. Exclamation mark.")
        )
    }

    @Test
    fun `Exclamation point at paragraph start`() {
        assertEquals(
            "!",
            process("Exclamation point.")
        )
    }

    @Test
    fun `lowercase exclamation point is NOT replaced (user talking about it)`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "An exclamation point is a type of punctuation"
            )
        )
    }

    @Test
    fun `lowercase exclamation point with period is NOT replaced`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "use an exclamation point."
            )
        )
    }

    // --- Question mark ---

    @Test
    fun `replaces Question mark after period`() {
        assertEquals(
            "Really?",
            process("Really. Question mark.")
        )
    }

    @Test
    fun `replaces Question mark with question trailing variant`() {
        assertEquals(
            "Really?",
            process("Really. Question mark?")
        )
    }

    @Test
    fun `Question mark at paragraph start`() {
        assertEquals(
            "?",
            process("Question mark.")
        )
    }

    @Test
    fun `lowercase question mark is NOT replaced`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "add a question mark here"
            )
        )
    }

    // --- Period / full stop ---

    @Test
    fun `replaces Period after question mark`() {
        assertEquals(
            "End.",
            process("End? Period.")
        )
    }

    @Test
    fun `replaces Full stop alias`() {
        assertEquals(
            "End.",
            process("End! Full stop.")
        )
    }

    @Test
    fun `Period at paragraph start`() {
        assertEquals(
            ".",
            process("Period.")
        )
    }

    // --- Colon ---

    @Test
    fun `replaces Colon after period`() {
        assertEquals(
            "Note:",
            process("Note. Colon:")
        )
    }

    @Test
    fun `replaces Colon with dot trailing`() {
        assertEquals(
            "Dear sir:",
            process("Dear sir. Colon.")
        )
    }

    @Test
    fun `Colon at paragraph start`() {
        assertEquals(
            ":",
            process("Colon.")
        )
    }

    @Test
    fun `lowercase colon is NOT replaced`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "use a colon there"
            )
        )
    }

    // --- Semicolon ---

    @Test
    fun `replaces Semicolon after period`() {
        assertEquals(
            "Also;",
            process("Also. Semicolon;")
        )
    }

    @Test
    fun `replaces Semicolon with dot trailing`() {
        assertEquals(
            "However;",
            process("However. Semicolon.")
        )
    }

    @Test
    fun `Semicolon at paragraph start`() {
        assertEquals(
            ";",
            process("Semicolon.")
        )
    }

    // --- Comma ---

    @Test
    fun `replaces Comma after period`() {
        assertEquals(
            "Hello,",
            process("Hello. Comma.")
        )
    }

    @Test
    fun `replaces Comma with comma trailing`() {
        assertEquals(
            "Hello,",
            process("Hello. Comma,")
        )
    }

    @Test
    fun `Comma at paragraph start`() {
        assertEquals(
            ",",
            process("Comma.")
        )
    }

    @Test
    fun `lowercase comma is NOT replaced`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "add a comma here"
            )
        )
    }

    // --- New line ---

    @Test
    fun `replaces New line after period`() {
        assertEquals(
            "First line.\n",
            process("First line. New line.")
        )
    }

    @Test
    fun `New line at paragraph start`() {
        assertEquals(
            "\n",
            process("New line.")
        )
    }

    @Test
    fun `lowercase new line is NOT replaced`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph(
                "start a new line here"
            )
        )
    }

    // --- New paragraph ---

    @Test
    fun `replaces New paragraph after period`() {
        assertEquals(
            "End of section.\n\n",
            process("End of section. New paragraph.")
        )
    }

    @Test
    fun `New paragraph at paragraph start`() {
        assertEquals(
            "\n\n",
            process("New paragraph.")
        )
    }

    // --- Hyphen ---

    @Test
    fun `replaces Hyphen after period`() {
        assertEquals(
            "Self-",
            process("Self. Hyphen.")
        )
    }

    @Test
    fun `Hyphen at paragraph start`() {
        assertEquals(
            "-",
            process("Hyphen.")
        )
    }

    // --- Dash ---

    @Test
    fun `replaces Dash after period`() {
        assertEquals(
            "Something — ",
            process("Something. Dash.")
        )
    }

    @Test
    fun `Dash at paragraph start`() {
        assertEquals(
            " — ",
            process("Dash.")
        )
    }

    // --- Open quote / Close quote ---

    @Test
    fun `replaces Open quote after period`() {
        assertEquals(
            "He said\"",
            process("He said. Open quote.")
        )
    }

    @Test
    fun `replaces Close quote after period`() {
        assertEquals(
            "Done\"",
            process("Done. Close quote.")
        )
    }

    // --- Open parenthesis / Close parenthesis ---

    @Test
    fun `replaces Open parenthesis after period`() {
        assertEquals(
            "See(",
            process("See. Open parenthesis.")
        )
    }

    @Test
    fun `replaces Close parenthesis after period`() {
        assertEquals(
            "Note)",
            process("Note. Close parenthesis.")
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
    fun `handles multiple commands in one paragraph`() {
        assertEquals(
            "Hello, are you sure? Yes!",
            process("Hello. Comma, are you sure. Question mark. Yes. Exclamation point.")
        )
    }

    // --- Filler fragments ---

    @Test
    fun `removes Um fragment at paragraph start`() {
        assertEquals(
            "I think we should go.",
            process("Um, I think we should go.")
        )
    }

    @Test
    fun `removes Uh fragment at paragraph start`() {
        assertEquals(
            "this should stay lowercase",
            process("Uh, this should stay lowercase")
        )
    }

    @Test
    fun `removes filler after soft pause punctuation`() {
        assertEquals(
            "I think we should go.",
            process("I think, um, we should go.")
        )
    }

    @Test
    fun `lowercases next word after mid-sentence filler removal`() {
        assertEquals(
            "I think we should go.",
            process("I think, um, We should go.")
        )
    }

    @Test
    fun `preserves casing after sentence-ending punctuation and filler removal`() {
        assertEquals(
            "First sentence. We should go.",
            process("First sentence. Um, We should go.")
        )
    }

    @Test
    fun `preserves pronoun I after filler removal`() {
        assertEquals(
            "I think I should go.",
            process("I think, um, I should go.")
        )
    }

    @Test
    fun `removes multiple filler fragments`() {
        assertEquals(
            "I think we should go.",
            process("Um, I think, uh, we should go.")
        )
    }

    @Test
    fun `removes filler-only paragraph`() {
        assertEquals(
            "",
            process("Um,")
        )
    }

    @Test
    fun `does not remove filler word without comma`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph("I said um because I was thinking")
        )
    }

    @Test
    fun `does not remove filler inside another word`() {
        assertNull(
            TranscriptPostProcessor.processCurrentParagraph("This hummus, tastes good")
        )
    }

    @Test
    fun `removes comma-and disfluency`() {
        assertEquals(
            "I think we should go.",
            process("I think we should go, and.")
        )
    }

    @Test
    fun `removes and-before-period disfluency`() {
        assertEquals(
            "I think we should go.",
            process("I think we should go and.")
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
        assertEquals("I\u2019ve tried", adjust("I\u2019ve tried", "so "))
    }

    @Test
    fun `casing preserved for acronyms`() {
        assertEquals("NASA launched", adjust("NASA launched", "yesterday "))
    }

    @Test
    fun `casing preserved for single uppercase letter (acronym initial)`() {
        assertEquals("A", adjust("A", "the "))
    }

    @Test
    fun `casing preserved for single letter mid-sentence avoids aPI artifacts`() {
        assertEquals("B", adjust("B", "and "))
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
        assertEquals("some text.", strip("some text.", "42 more"))
    }
}
