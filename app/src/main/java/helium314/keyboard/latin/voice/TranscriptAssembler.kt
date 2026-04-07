// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Assembles an in-progress utterance from Deepgram's three transcript signals:
 * - Interim (is_final=false): fast preliminary guess, will be revised
 * - Finalized segment (is_final=true, speech_final=false): locked word(s), speaker continues
 * - Utterance complete (is_final=true, speech_final=true): speaker paused, commit everything
 *
 * This class is pure state + logic with no Android dependencies — easy to unit test.
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

    /** Update the interim portion and return the full display text. */
    fun onInterim(text: String): String {
        currentInterim = text
        return getDisplayText()
    }

    /** Lock in a finalized segment (speaker continues) and return the full display text. */
    fun onFinalizedSegment(text: String): String {
        if (text.isNotBlank()) finalizedSegments.add(text)
        currentInterim = ""
        return getDisplayText()
    }

    /**
     * Speaker paused — incorporate the final segment and return the complete utterance text.
     * Resets internal state so the next utterance starts fresh.
     */
    fun onUtteranceComplete(text: String): String {
        if (text.isNotBlank()) finalizedSegments.add(text)
        val full = finalizedSegments.joinToString(" ")
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
