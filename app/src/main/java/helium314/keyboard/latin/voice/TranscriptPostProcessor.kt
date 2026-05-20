// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Post-processes transcribed text at the paragraph level to fix patterns that
 * the realtime STT provider cannot handle — primarily spelled-out punctuation
 * names that the speaker dictates as voice commands (e.g. "exclamation point",
 * "comma").
 *
 * Rules are applied case-insensitively, longest match first, so that patterns
 * with surrounding punctuation context (like ". Exclamation point.") are consumed
 * before shorter ambiguous ones (like "exclamation point.").
 */
object TranscriptPostProcessor {

    data class Rule(val find: String, val replace: String)

    val rules: List<Rule> = buildRules()

    /**
     * Analyze [paragraph] and return the corrected text, or `null` if no rules matched.
     */
    fun processCurrentParagraph(paragraph: String): String? {
        var result = paragraph
        for (rule in rules) {
            result = result.replace(rule.find, rule.replace)
        }
        return if (result != paragraph) result else null
    }

    /**
     * Strip trailing sentence-ending punctuation from [chunk] if [followingContext]
     * shows the cursor is mid-sentence (next visible character is a lowercase letter).
     *
     * Realtime STT providers tend to append end-of-sentence punctuation to every
     * transcript span. When the caret is positioned inside existing text, that
     * trailing mark is wrong because the text that follows is a continuation of
     * the same sentence.
     */
    fun stripTrailingPunctuationIfMidSentence(chunk: String, followingContext: CharSequence): String {
        if (chunk.isEmpty()) return chunk
        val last = chunk[chunk.length - 1]
        if (last != '.' && last != '!' && last != '?') return chunk
        if (!isSentenceContinuation(followingContext)) return chunk
        return chunk.substring(0, chunk.length - 1)
    }

    private fun isSentenceContinuation(context: CharSequence): Boolean {
        for (c in context) {
            if (c == '\n' || c == '\r') return false
            if (c.isWhitespace()) continue
            return c.isLetter() && c.isLowerCase()
        }
        return false
    }

    /**
     * Adjust the first character's casing of a freshly arrived transcription [chunk].
     *
     * Realtime STT providers typically capitalize the first letter of a new
     * transcript span because they treat each span as the start of a new
     * sentence. When the user dictates mid-sentence (caret placed inside
     * existing text, or continuing after deleting the preceding punctuation),
     * that sentence-start capitalization is wrong. This helper lowercases the
     * first letter only when the editor [previousContext] clearly shows we are
     * NOT at a sentence boundary.
     *
     * Capitalization is preserved when any of the following is true:
     *  - the chunk is empty or its first letter is not an uppercase letter
     *  - [previousContext] is empty or only whitespace (start of the editor)
     *  - the last visible character (ignoring trailing whitespace and closing
     *    quotes/brackets) is a sentence-ending mark: `.`, `!`, `?`, or a newline
     *  - the first word is the pronoun "I" (or "I'm", "I'll", "I've", "I'd")
     *  - the first word is all-uppercase (acronyms like "NASA")
     *  - the first word has additional uppercase letters beyond position 0
     *    (camel/Pascal case like "iPhone", "McDonald's")
     */
    fun adjustLeadingCasing(chunk: String, previousContext: CharSequence): String {
        if (chunk.isEmpty()) return chunk
        val first = chunk[0]
        if (!first.isLetter() || !first.isUpperCase()) return chunk

        val firstWord = extractFirstWord(chunk)
        if (shouldPreserveWordCasing(firstWord)) return chunk

        if (isAtSentenceBoundary(previousContext)) return chunk

        return first.lowercaseChar() + chunk.substring(1)
    }

    private fun extractFirstWord(chunk: String): String {
        val end = chunk.indexOfFirst { c ->
            !c.isLetter() && c != '\'' && c != '’'
        }
        return if (end < 0) chunk else chunk.substring(0, end)
    }

    private fun shouldPreserveWordCasing(word: String): Boolean {
        if (word.isEmpty()) return true
        // A single uppercase letter is likely an acronym initial being delivered
        // as its own finalized span. Lowercasing it would produce artifacts
        // like "aPI" when the following attached span keeps its uppercase.
        if (word.length == 1) return true
        // Keep pronoun "I" and its contractions.
        if (word == "I" || word.startsWith("I'") || word.startsWith("I’")) return true
        // Keep camel/Pascal-case and acronyms: any uppercase letter after index 0.
        for (i in 1 until word.length) {
            if (word[i].isUpperCase()) return true
        }
        return false
    }

    private fun isAtSentenceBoundary(context: CharSequence): Boolean {
        var i = context.length - 1
        while (i >= 0) {
            val c = context[i]
            when {
                c == '\n' || c == '\r' -> return true
                c == '.' || c == '!' || c == '?' -> return true
                c.isWhitespace() -> i--
                c == '"' || c == '\'' || c == ')' || c == ']' || c == '}' ||
                    c == '“' || c == '”' ||
                    c == '‘' || c == '’' ||
                    c == '»' -> i--
                else -> return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Rule construction — only matches capitalized sentence-form commands
    // as returned by real-time STT providers (e.g. "Colon." not "colon").
    //
    // When the user speaks a punctuation command after a pause, the provider
    // returns it as its own sentence: capitalized, with trailing punctuation.
    // If the same word appears lowercase mid-sentence, the user is talking
    // ABOUT the punctuation, not commanding it. We only replace the
    // sentence-form command.
    //
    // Rules are sorted by find-length descending so the most specific
    // (longest) patterns are tried first.
    // ------------------------------------------------------------------

    private fun buildRules(): List<Rule> {
        val raw = mutableListOf<Rule>()

        // Helper: for a given punctuation command name (capitalized), add rules
        // that match the command preceded by sentence-ending context or at the
        // start of the paragraph, always requiring trailing punctuation.
        fun addCommandRules(
            names: List<String>,
            replacement: String,
            trailingVariants: List<String>,
            leadingContexts: List<String> = listOf(". ", "? ", "! ", ", ")
        ) {
            for (name in names) {
                for (trailing in trailingVariants) {
                    for (leading in leadingContexts) {
                        raw += Rule("$leading$name$trailing", replacement)
                    }
                    // At paragraph start (no leading context)
                    raw += Rule("$name$trailing", replacement)
                }
            }
        }

        // --- Punctuation commands (capitalized, with trailing punctuation) ---

        addCommandRules(
            names = listOf("Exclamation point", "Exclamation mark"),
            replacement = "!",
            trailingVariants = listOf(".", "!")
        )

        addCommandRules(
            names = listOf("Question mark"),
            replacement = "?",
            trailingVariants = listOf("?", ".")
        )

        addCommandRules(
            names = listOf("Period", "Full stop"),
            replacement = ".",
            trailingVariants = listOf(".")
        )

        addCommandRules(
            names = listOf("Colon"),
            replacement = ":",
            trailingVariants = listOf(":", ".")
        )

        addCommandRules(
            names = listOf("Semicolon"),
            replacement = ";",
            trailingVariants = listOf(";", ".")
        )

        addCommandRules(
            names = listOf("Comma"),
            replacement = ",",
            trailingVariants = listOf(",", ".")
        )

        // --- Structural commands (preserve leading sentence punctuation) ---

        for (leading in listOf(". ", "? ", "! ", ", ")) {
            val punct = leading.trimEnd()
            raw += Rule("${leading}New line.", "$punct\n")
        }
        raw += Rule("New line.", "\n")

        for (leading in listOf(". ", "? ", "! ", ", ")) {
            val punct = leading.trimEnd()
            raw += Rule("${leading}New paragraph.", "$punct\n\n")
        }
        raw += Rule("New paragraph.", "\n\n")

        // --- Symbol commands ---

        addCommandRules(
            names = listOf("Hyphen"),
            replacement = "-",
            trailingVariants = listOf(".", "-")
        )

        addCommandRules(
            names = listOf("Dash"),
            replacement = " — ",
            trailingVariants = listOf(".")
        )

        addCommandRules(
            names = listOf("Open quote"),
            replacement = "\"",
            trailingVariants = listOf(".")
        )

        addCommandRules(
            names = listOf("Close quote"),
            replacement = "\"",
            trailingVariants = listOf(".")
        )

        addCommandRules(
            names = listOf("Open parenthesis"),
            replacement = "(",
            trailingVariants = listOf(".")
        )

        addCommandRules(
            names = listOf("Close parenthesis"),
            replacement = ")",
            trailingVariants = listOf(".")
        )

        return raw.sortedByDescending { it.find.length }
    }
}
