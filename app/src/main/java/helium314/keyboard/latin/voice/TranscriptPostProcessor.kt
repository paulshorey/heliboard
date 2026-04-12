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

        // --- Exclamation point / exclamation mark → ! -----------------
        for (name in listOf("exclamation point", "exclamation mark")) {
            for (trailing in listOf(".", "!")) {
                for (leading in listOf(". ", "? ", "! ", ", ")) {
                    raw += Rule("$leading$name$trailing", "!")
                }
                raw += Rule("$name$trailing", "!")
            }
        }

        // --- Question mark → ? ----------------------------------------
        for (trailing in listOf("?", ".")) {
            for (leading in listOf(". ", "? ", "! ", ", ")) {
                raw += Rule("${leading}question mark$trailing", "?")
            }
            raw += Rule("question mark$trailing", "?")
        }

        // --- Period / full stop → . -----------------------------------
        for (name in listOf("period", "full stop")) {
            for (leading in listOf("? ", "! ", ", ")) {
                raw += Rule("$leading$name.", ".")
            }
            raw += Rule("$name.", ".")
        }

        // --- Comma → , ------------------------------------------------
        for (leading in listOf(". ", "? ", "! ", ", ")) {
            raw += Rule("${leading}comma,", ",")
            raw += Rule("${leading}comma", ",")
        }
        raw += Rule("comma,", ",")

        // --- Colon → : ------------------------------------------------
        for (trailing in listOf(":", ".")) {
            for (leading in listOf(". ", "? ", "! ", ", ")) {
                raw += Rule("${leading}colon$trailing", ":")
            }
            raw += Rule("colon$trailing", ":")
        }

        // --- Semicolon → ; --------------------------------------------
        for (trailing in listOf(";", ".")) {
            for (leading in listOf(". ", "? ", "! ", ", ")) {
                raw += Rule("${leading}semicolon$trailing", ";")
            }
            raw += Rule("semicolon$trailing", ";")
        }

        return raw.sortedByDescending { it.find.length }
    }
}
