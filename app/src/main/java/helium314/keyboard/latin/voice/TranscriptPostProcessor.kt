// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Post-processes transcribed text at the paragraph level to fix patterns that
 * the realtime STT provider cannot handle — primarily filler fragments (e.g.
 * "um,", "uh,") and spelled-out punctuation names that the speaker dictates
 * as voice commands (e.g. "exclamation point", "comma").
 *
 * Rules are applied case-insensitively, longest match first, so that patterns
 * with surrounding punctuation context (like ". Exclamation point.") are consumed
 * before shorter ambiguous ones (like "exclamation point.").
 *
 * When [removeCommas] is true, every ASCII comma is stripped as a final pass
 * after filler cleanup and spoken-command replacements.
 */
object TranscriptPostProcessor {

    data class Rule(val find: String, val replace: String)

    val rules: List<Rule> = buildRules()

    private val disfluencyReplacements = listOf(
        Rule("—", ""),
        Rule(", hmm.", ""),
        Rule(" hmm.", ""),
        Rule("hmm.", ""),
        Rule(", um.", "."),
        Rule(" um.", "."),
        Rule(", uh.", "."),
        Rule(" uh.", "."),
        Rule(", and.", "."),
        Rule(" and.", "."),
    )

    /**
     * Analyze [paragraph] and return the corrected text, or `null` if no rules matched.
     *
     * @param removeCommas when true, strip all ASCII commas after every other
     *   transformation. Defaults to false so existing call sites and unit tests
     *   keep commas unless the transcription preference is enabled.
     */
    fun processCurrentParagraph(
        paragraph: String,
        removeCommas: Boolean = false
    ): String? {
        var result = removeFillerFragments(paragraph)
        for (rule in disfluencyReplacements) {
            result = result.replace(rule.find, rule.replace)
        }
        for (rule in rules) {
            result = result.replace(rule.find, rule.replace)
        }
        if (removeCommas) {
            result = removeAllCommas(result)
        }
        return if (result != paragraph) result else null
    }

    /**
     * Strip every ASCII comma. Runs last so spoken "Comma" commands and filler
     * patterns that depend on commas have already been applied.
     */
    internal fun removeAllCommas(text: String): String {
        if (text.isEmpty() || text.indexOf(',') < 0) return text
        return text.replace(",", "")
    }

    /**
     * Remove common dictated filler fragments that Soniox returns as text.
     *
     * Soniox typically smart-formats disfluencies as short comma-attached
     * fragments ("um,", "uh,"). Cleaning at paragraph level handles both
     * within-chunk fillers and fillers split across finalized chunks, because
     * [LatinIME][helium314.keyboard.latin.LatinIME] runs this pass after each
     * commit inside the same batch edit.
     */
    internal fun removeFillerFragments(text: String): String {
        if (text.isEmpty()) return text

        val result = StringBuilder(text.length)
        var cursor = 0
        var scan = 0
        while (scan < text.length) {
            val fillerLength = fillerLengthAt(text, scan)
            if (fillerLength == 0) {
                scan++
                continue
            }

            val removalStart = findFillerRemovalStart(text, scan)
            var removalEnd = scan + fillerLength + 1 // include the required comma
            while (removalEnd < text.length && isHorizontalWhitespace(text[removalEnd])) {
                removalEnd++
            }

            result.append(text, cursor, removalStart)

            val before = previousChar(text, removalStart)
            val after = text.getOrNull(removalEnd)
            result.append(separatorAfterFillerRemoval(before, after))

            cursor = removalEnd
            if (shouldLowercaseAfterFillerRemoval(text, removalStart, cursor)) {
                result.append(text[cursor].lowercaseChar())
                cursor++
            }
            scan = cursor
        }

        if (cursor == 0) return text
        result.append(text, cursor, text.length)
        return result.toString()
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

    private fun fillerLengthAt(text: String, index: Int): Int {
        if (index > 0 && isWordCharacter(text[index - 1])) return 0

        val length = when {
            text.regionMatches(index, "uh", 0, 2, ignoreCase = true) -> 2
            text.regionMatches(index, "um", 0, 2, ignoreCase = true) -> 2
            else -> return 0
        }
        val commaIndex = index + length
        if (commaIndex >= text.length || text[commaIndex] != ',') return 0
        return length
    }

    private fun findFillerRemovalStart(text: String, fillerStart: Int): Int {
        var start = fillerStart
        while (start > 0 && isHorizontalWhitespace(text[start - 1])) {
            start--
        }
        if (start > 0 && isSoftPausePunctuation(text[start - 1])) {
            start--
            while (start > 0 && isHorizontalWhitespace(text[start - 1])) {
                start--
            }
        }
        return start
    }

    private fun separatorAfterFillerRemoval(before: Char?, after: Char?): String {
        if (before == null || after == null) return ""
        if (before.isWhitespace() || after.isWhitespace()) return ""
        if (isOpeningDelimiter(before) || isBackwardAttachingPunctuation(after)) return ""
        return " "
    }

    private fun shouldLowercaseAfterFillerRemoval(
        text: String,
        removalStart: Int,
        afterIndex: Int
    ): Boolean {
        if (afterIndex >= text.length) return false
        val first = text[afterIndex]
        if (!first.isLetter() || !first.isUpperCase()) return false
        if (isAtSentenceBoundary(text.subSequence(0, removalStart))) return false

        val firstWord = extractFirstWord(text.substring(afterIndex))
        return !shouldPreserveWordCasing(firstWord)
    }

    private fun previousChar(text: String, index: Int): Char? {
        return if (index > 0) text[index - 1] else null
    }

    private fun isWordCharacter(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '\'' || c == '’'
    }

    private fun isHorizontalWhitespace(c: Char): Boolean {
        return c == ' ' || c == '\t'
    }

    private fun isSoftPausePunctuation(c: Char): Boolean {
        return c == ',' || c == ';' || c == ':'
    }

    private fun isOpeningDelimiter(c: Char): Boolean {
        return c == '(' || c == '[' || c == '{' || c == '"' ||
            c == '“' || c == '‘'
    }

    private fun isBackwardAttachingPunctuation(c: Char): Boolean {
        return c == '.' || c == ',' || c == '!' || c == '?' ||
            c == ':' || c == ';' || c == ')' || c == ']' ||
            c == '}' || c == '%'
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
