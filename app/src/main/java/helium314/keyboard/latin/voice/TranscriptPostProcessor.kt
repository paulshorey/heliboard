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
            result = result.replace(rule.find, rule.replace, ignoreCase = true)
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
    // Rule construction — sorted by find-length descending so the most
    // specific (longest) patterns are tried first.
    // ------------------------------------------------------------------

    private fun buildRules(): List<Rule> {
        val raw = mutableListOf<Rule>()

        // Helper: for a given punctuation name, add rules with every combination
        // of leading context (previous punctuation or a plain space) and trailing
        // punctuation variant.  Rules with leading punctuation context are the
        // longest; the space-prefixed standalone variant is shorter; the bare
        // variant (for paragraph start) is shortest.
        fun addPuncRules(
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
                    raw += Rule(" $name$trailing", replacement)
                    raw += Rule("$name$trailing", replacement)
                }
            }
        }

        addPuncRules(
            names = listOf("exclamation point", "exclamation mark"),
            replacement = "!",
            trailingVariants = listOf(".", "!")
        )

        addPuncRules(
            names = listOf("question mark"),
            replacement = "?",
            trailingVariants = listOf("?", ".")
        )

        addPuncRules(
            names = listOf("period", "full stop"),
            replacement = ".",
            trailingVariants = listOf(".")
        )

        addPuncRules(
            names = listOf("colon"),
            replacement = ":",
            trailingVariants = listOf(":", ".")
        )

        addPuncRules(
            names = listOf("semicolon"),
            replacement = ";",
            trailingVariants = listOf(";", ".")
        )

        // Comma is special — trailing can be the comma itself, or absent.
        for (leading in listOf(". ", "? ", "! ", ", ")) {
            raw += Rule("${leading}comma,", ",")
            raw += Rule("${leading}comma", ",")
        }
        raw += Rule(" comma,", ",")
        raw += Rule(" comma", ",")
        raw += Rule("comma,", ",")

        return raw.sortedByDescending { it.find.length }
    }
}
