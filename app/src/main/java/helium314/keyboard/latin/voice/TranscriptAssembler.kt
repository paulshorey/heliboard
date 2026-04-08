// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Assembles Deepgram's three transcript signals (interim, finalized segment,
 * utterance complete) into display text and commit text.
 *
 * Holds the state for one in-progress utterance: a list of finalized segments
 * plus the latest interim text. Callers use [getDisplayText] to show the current
 * composing region and [onUtteranceComplete] to get the final committed string.
 */
class TranscriptAssembler {
    private val finalizedSegments = mutableListOf<String>()
    private var currentInterim: String = ""

    /** What should be displayed right now in the composing region. */
    fun getDisplayText(): String {
        val finalized = finalizedSegments.joinToString(" ")
        return when {
            finalized.isNotEmpty() && currentInterim.isNotEmpty() -> "$finalized $currentInterim"
            finalized.isNotEmpty() -> finalized
            else -> currentInterim
        }
    }

    fun onInterim(text: String): String {
        currentInterim = text
        return getDisplayText()
    }

    fun onFinalizedSegment(text: String): String {
        if (text.isNotBlank()) finalizedSegments.add(text)
        currentInterim = ""
        return getDisplayText()
    }

    fun onUtteranceComplete(text: String): String {
        if (text.isNotBlank()) finalizedSegments.add(text)
        var full = finalizedSegments.joinToString(" ")
        // Add trailing period at utterance boundary if text doesn't already end with punctuation.
        // This ensures sentences are properly terminated when the speaker pauses.
        if (full.isNotBlank()) {
            val lastChar = full.last()
            if (lastChar != '.' && lastChar != '!' && lastChar != '?'
                && lastChar != ',' && lastChar != ';' && lastChar != ':') {
                full = "$full."
            }
        }
        reset()
        return full
    }

    fun reset() {
        finalizedSegments.clear()
        currentInterim = ""
    }

    fun hasContent(): Boolean =
        finalizedSegments.isNotEmpty() || currentInterim.isNotEmpty()
}
