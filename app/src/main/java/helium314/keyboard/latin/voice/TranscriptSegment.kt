// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Finalized transcript chunk produced by a streaming transcription client.
 *
 * @property text Already-trimmed UTF-16 text ready to be committed at the caret.
 * @property attachesToPrevious True when [text] starts with punctuation that
 *   should hug the previous word (so the IME suppresses an auto-leading space).
 */
data class TranscriptSegment(
    val text: String,
    val attachesToPrevious: Boolean
)
