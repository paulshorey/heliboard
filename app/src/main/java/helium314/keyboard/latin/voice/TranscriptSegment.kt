// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Finalized transcript chunk produced by a streaming transcription client.
 *
 * @property text Already-trimmed UTF-16 text ready to be committed at the caret.
 * @property attachesToPrevious True when [text] should be appended directly to
 *   the previous text without an injected separator space. This covers two
 *   cases:
 *   1. The chunk starts with punctuation that should hug the previous word
 *      (`,`, `.`, `!`, `?`, …).
 *   2. The chunk continues a word that the upstream provider split across
 *      consecutive responses (e.g. provider finalizes `"head"` then emits
 *      `"ing"` for the rest of `"heading"` with no leading space token). In
 *      this case attaching prevents `"head ing"` artifacts.
 */
data class TranscriptSegment(
    val text: String,
    val attachesToPrevious: Boolean
)
