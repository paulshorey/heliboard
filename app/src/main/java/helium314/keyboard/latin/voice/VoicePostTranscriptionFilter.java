// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.Nullable;

/**
 * Centralized text preparation for finalized voice transcripts before insertion.
 *
 * <p>This keeps all deterministic voice-specific string shaping in one place so the IME only
 * needs to commit the already-prepared text into the editor.</p>
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
     * Apply deterministic text-only post-transcription rules.
     *
     * <p>This is the hook for future substitutions such as spoken punctuation and number
     * conversions. It intentionally stays independent of editor context.</p>
     */
    public static String applyPostTranscriptionFilter(@Nullable final String text) {
        if (text == null) {
            return "";
        }
        return text;
    }

    /**
     * Prepare finalized transcript text for insertion into the editor.
     *
     * <p>Applies deterministic voice-specific cleanup, then context-aware capitalization, and
     * finally normalizes trailing spacing so step 3 only needs to commit the result.</p>
     */
    public static String prepareForInsertion(
            @Nullable final String rawText,
            @Nullable final CharSequence textBeforeCursor
    ) {
        final String filtered = applyPostTranscriptionFilter(rawText);
        final String sanitized = VoiceTextSanitizer.stripInvisibleChars(filtered).trim();
        if (sanitized.isEmpty()) {
            return sanitized;
        }

        final String capitalized = adjustCapitalization(sanitized, textBeforeCursor);
        return ensureTrailingSpace(capitalized);
    }

    private static String adjustCapitalization(
            final String text,
            @Nullable final CharSequence textBeforeCursor
    ) {
        if (text.isEmpty() || textBeforeCursor == null || textBeforeCursor.length() == 0) {
            return text;
        }

        final int lastVisibleChar = findLastVisibleChar(textBeforeCursor);
        if (lastVisibleChar == -1 || isSentenceBoundary(lastVisibleChar)) {
            return text;
        }

        final char firstChar = text.charAt(0);
        if (!Character.isUpperCase(firstChar)) {
            return text;
        }
        return Character.toLowerCase(firstChar) + text.substring(1);
    }

    private static int findLastVisibleChar(final CharSequence text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            final char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return -1;
    }

    private static boolean isSentenceBoundary(final int c) {
        return c == '.' || c == '!' || c == '?' || c == '\n';
    }

    private static String ensureTrailingSpace(final String text) {
        if (text.isEmpty() || text.endsWith(" ")) {
            return text;
        }
        return text + " ";
    }
}
