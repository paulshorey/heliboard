// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Project attributed words onto the provider's formatted text without inventing
 * punctuation or searching for repeated phrases. A linear lexical alignment must
 * account for every letter/digit in both representations. If it cannot, callers
 * retain attributed tokens rather than risk inserting unattributed words.
 */
internal object GrokFormattedTranscript {
    fun align(text: String, words: List<String>): List<String>? {
        val positions = text.indices.filter { lexical(text[it]) }
        val native = positions.map { text[it].lowercaseChar() }.joinToString("")
        val tokens = words.map { word -> word.filter(::lexical).map { it.lowercaseChar() }.joinToString("") }
        if (native.isEmpty() || tokens.joinToString("") != native) return null
        val indices = tokens.indices.filter { tokens[it].isNotEmpty() }
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var offset = 0
        for (i in indices) {
            starts.add(positions[offset])
            offset += tokens[i].length
            ends.add(positions[offset - 1] + 1)
        }
        val result = MutableList(words.size) { "" }
        var left = 0
        for (i in indices.indices) {
            val right = if (i == indices.lastIndex) text.length else {
                val gap = text.substring(ends[i], starts[i + 1])
                val space = gap.indexOfLast { it.isWhitespace() }
                val opening = gap.indices.firstOrNull { p -> gap[p] in "([{“‘\$£€¥#￥＄" ||
                    gap[p] == '"' && p > 0 && gap[p - 1].isWhitespace() } ?: -1
                ends[i] + when {
                    opening >= 0 && space >= 0 -> minOf(opening, space + 1)
                    space >= 0 -> space + 1
                    opening >= 0 -> opening
                    else -> gap.length
                }
            }
            // Punctuation-only tokens have no lexical identity. Native punctuation
            // travels with the adjacent formatted lexical span, once, not twice.
            result[indices[i]] = text.substring(left, right)
            left = right
        }
        return result
    }

    private fun lexical(c: Char) = c.isLetterOrDigit() || Character.getType(c).let {
        // Symbols such as emoji and '+' are content, not removable formatting.
        // Surrogate pairs are retained as both UTF-16 units, so an attributed
        // emoji token cannot be borrowed by an adjacent selected word.
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
            it == Character.OTHER_SYMBOL.toInt() || it == Character.MATH_SYMBOL.toInt() ||
            it == Character.SURROGATE.toInt()
    }
}
