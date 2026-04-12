// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Post-processes transcribed text at the paragraph level to fix patterns that
 * Speechmatics cannot handle — primarily spelled-out punctuation names that the
 * speaker dictates as voice commands (e.g. "exclamation point", "comma").
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
