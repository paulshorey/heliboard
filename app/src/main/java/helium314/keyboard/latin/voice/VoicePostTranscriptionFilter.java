// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.Nullable;

/**
 * Optional hook for deterministic post-transcription filtering on each finalized chunk
 * before cleanup and insertion.
 *
 * <p>Reserved for future rules (e.g. length-based filtering). The character threshold below
 * is kept for upcoming use.</p>
 */
public final class VoicePostTranscriptionFilter {

    /**
     * Reserved for future filtering that may treat short chunks differently (e.g. length cutoff).
     */
    public static final int POST_TRANSCRIPTION_FILTER_SHORT_CHUNK_CHAR_LIMIT = 40;

    private VoicePostTranscriptionFilter() {
        // Utility class.
    }

    /**
     * Apply post-transcription filtering. Currently returns the input unchanged.
     */
    public static String applyPostTranscriptionFilter(@Nullable final String text) {
        if (text == null) {
            return "";
        }
        return text;
    }
}
