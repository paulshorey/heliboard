// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.Nullable;

/**
 * Sanitizes voice text returned by remote transcription and cleanup APIs.
 *
 * <p>These APIs occasionally emit invisible Unicode formatting controls. They render with no
 * width, but still occupy cursor positions, so backspace appears to get "stuck" while it deletes
 * hidden characters one by one. We strip all Unicode format controls plus a small set of
 * additional historical zero-width code points that Android can surface in editor text.</p>
 */
public final class VoiceTextSanitizer {
    private VoiceTextSanitizer() {
        // Utility class.
    }

    public static String stripInvisibleChars(@Nullable final String text) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty()) {
            return text;
        }

        StringBuilder sanitized = null;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            final int charCount = Character.charCount(codePoint);
            if (shouldStrip(codePoint)) {
                if (sanitized == null) {
                    sanitized = new StringBuilder(text.length());
                    sanitized.append(text, 0, i);
                }
            } else if (sanitized != null) {
                sanitized.appendCodePoint(codePoint);
            }
            i += charCount;
        }
        return sanitized == null ? text : sanitized.toString();
    }

    private static boolean shouldStrip(final int codePoint) {
        final int type = Character.getType(codePoint);
        if (type == Character.FORMAT) {
            return true;
        }
        if (type == Character.CONTROL) {
            return codePoint != '\n' && codePoint != '\r' && codePoint != '\t';
        }

        // Additional zero-width characters that are not FORMAT chars on Android.
        return codePoint == 0x034F  // Combining grapheme joiner
                || codePoint == 0x115F  // Hangul choseong filler
                || codePoint == 0x1160  // Hangul jungseong filler
                || codePoint == 0x17B4  // Khmer vowel inherent AQ
                || codePoint == 0x17B5; // Khmer vowel inherent AA
    }
}
