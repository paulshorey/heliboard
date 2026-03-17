// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Helpers for building a bounded cleanup context window for voice transcription.
 *
 * <p>The editable portion must stay limited to the latest line so the IME can safely
 * replace only text immediately before the cursor. Earlier lines and paragraphs are
 * read-only context for the cleanup model.</p>
 */
public final class VoiceContextUtils {
    private static final int DEFAULT_BOUNDARY_COUNT = 3;
    private static final int DEFAULT_FALLBACK_CHARS = 300;

    private VoiceContextUtils() {
        // Utility class.
    }

    @NonNull
    public static String extractRecentContext(@Nullable final CharSequence beforeCursor) {
        return extractRecentContext(beforeCursor, DEFAULT_BOUNDARY_COUNT, DEFAULT_FALLBACK_CHARS);
    }

    @NonNull
    public static String extractRecentContext(
            @Nullable final CharSequence beforeCursor,
            final int boundaryCountTarget,
            final int fallbackChars) {
        if (beforeCursor == null || beforeCursor.length() == 0) {
            return "";
        }
        final String text = beforeCursor.toString();
        if (text.isEmpty()) {
            return "";
        }

        int boundaryCount = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            final char c = text.charAt(i);
            if (isLineBreak(c)) {
                final int boundaryEndExclusive = i + 1;
                while (i > 0 && isLineBreak(text.charAt(i - 1))) {
                    i--;
                }
                boundaryCount++;
                if (boundaryCount >= boundaryCountTarget) {
                    return text.substring(boundaryEndExclusive);
                }
                continue;
            }
            if (isSentencePunctuation(c)) {
                boundaryCount++;
                if (boundaryCount >= boundaryCountTarget) {
                    return text.substring(i + 1);
                }
            }
        }

        if (text.length() <= fallbackChars) {
            return text;
        }
        return text.substring(text.length() - fallbackChars);
    }

    @NonNull
    public static CleanupWindow splitForCleanup(@NonNull final String recentContext) {
        final int lastLineBreak = findLastLineBreakIndex(recentContext);
        if (lastLineBreak < 0) {
            return new CleanupWindow("", recentContext);
        }
        return new CleanupWindow(
                recentContext.substring(0, lastLineBreak + 1),
                recentContext.substring(lastLineBreak + 1)
        );
    }

    private static int findLastLineBreakIndex(@NonNull final String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (isLineBreak(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isLineBreak(final char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean isSentencePunctuation(final char c) {
        return c == '.' || c == '!' || c == '?' || c == ':' || c == ';' || c == '=';
    }

    public static final class CleanupWindow {
        @NonNull
        private final String referenceContext;
        @NonNull
        private final String editableText;

        public CleanupWindow(@NonNull final String referenceContext, @NonNull final String editableText) {
            this.referenceContext = referenceContext;
            this.editableText = editableText;
        }

        @NonNull
        public String getReferenceContext() {
            return referenceContext;
        }

        @NonNull
        public String getEditableText() {
            return editableText;
        }
    }
}
