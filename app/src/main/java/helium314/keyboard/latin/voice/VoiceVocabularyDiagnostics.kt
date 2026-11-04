// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** Counts correlations with sent hints; never retains or returns transcript text. */
internal class VoiceVocabularyDiagnostics(terms: List<String>, editorTerms: List<String>) {
    private data class Hint(val pattern: Regex, val fromEditor: Boolean)

    private val hints = terms.map { term ->
        Hint(
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE),
            term in editorTerms,
        )
    }

    data class Counts(val matches: Int, val editorMatches: Int, val quotedMatches: Int) {
        override fun toString() = "matches=$matches,editorMatches=$editorMatches,quotedMatches=$quotedMatches"
    }

    fun count(text: String): Counts {
        var matches = 0
        var editorMatches = 0
        var quotedMatches = 0
        for (hint in hints) {
            for (match in hint.pattern.findAll(text)) {
                matches++
                if (hint.fromEditor) editorMatches++
                val opening = text.getOrNull(match.range.first - 1)
                val closing = text.getOrNull(match.range.last + 1)
                if ((opening == '\'' && closing == '\'') ||
                    (opening == '\u2018' && closing == '\u2019')) quotedMatches++
            }
        }
        return Counts(matches, editorMatches, quotedMatches)
    }
}
