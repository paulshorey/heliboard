/*
 * Copyright (C) 2024 HeliBoard Contributors
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.text.TextUtils;

/**
 * Utility class for smart auto-capitalization.
 * <p>
 * This provides sentence-boundary detection that works independently of signals from the
 * text field. This is useful for Android apps that don't properly send the "start of
 * new sentence" signal to the keyboard.
 * <p>
 * Capitalization rules:
 * 1. If there are no previous characters - capitalize
 * 2. If previous character (ignoring spaces) was a sentence terminator (. ? !) followed by a space - capitalize
 * 3. If previous character (ignoring spaces and tabs) was a newline/line break/carriage return - capitalize
 * 4. Otherwise - do not capitalize
 */
public final class SmartAutoCapsUtils {
    private SmartAutoCapsUtils() {
        // This utility class is not publicly instantiable.
    }

    /**
     * Determines if the next character should be capitalized based on the text before the cursor.
     * <p>
     * This method implements simple sentence-boundary detection that doesn't rely on
     * signals from the text field.
     *
     * @param textBeforeCursor The text before the cursor position.
     * @param hasSpaceBefore Whether there's a phantom space that will be inserted before the next character.
     * @return true if the next character should be capitalized, false otherwise.
     */
    public static boolean shouldCapitalize(final CharSequence textBeforeCursor, final boolean hasSpaceBefore) {
        // Rule 1: If there are no previous characters, capitalize
        if (TextUtils.isEmpty(textBeforeCursor)) {
            return true;
        }

        // Find the last non-whitespace character (ignoring spaces and tabs, but considering newlines)
        int index = textBeforeCursor.length() - 1;
        int spacesSkipped = 0;

        // If we have a phantom space, count it as a skipped space
        if (hasSpaceBefore) {
            spacesSkipped = 1;
        }

        // Skip spaces and tabs (but NOT newlines - those are significant)
        while (index >= 0) {
            final char c = textBeforeCursor.charAt(index);
            if (c == ' ' || c == '\t') {
                spacesSkipped++;
                index--;
            } else {
                break;
            }
        }

        // Rule 1 (extended): If we've skipped all characters, capitalize
        if (index < 0) {
            return true;
        }

        final char lastSignificantChar = textBeforeCursor.charAt(index);

        // Rule 3: If previous significant character is a newline, capitalize
        if (lastSignificantChar == '\n' || lastSignificantChar == '\r') {
            return true;
        }

        // Rule 2: If previous significant character is a sentence terminator AND
        // there's at least one space after it, capitalize
        // This ensures we don't capitalize immediately after typing "." but only after ". "
        if (isSentenceTerminator(lastSignificantChar) && spacesSkipped > 0) {
            return true;
        }

        // Rule 4: Otherwise, do not capitalize
        return false;
    }

    /**
     * Checks if the given character is a sentence terminator.
     * <p>
     * This includes periods, question marks, and exclamation marks.
     *
     * @param c The character to check.
     * @return true if the character is a sentence terminator.
     */
    private static boolean isSentenceTerminator(final char c) {
        return c == '.' || c == '?' || c == '!' ||
               // Also include some Unicode sentence terminators
               c == '\u3002' || // Chinese/Japanese full stop
               c == '\uFF01' || // Fullwidth exclamation mark
               c == '\uFF1F';   // Fullwidth question mark
    }

    /**
     * Similar to shouldCapitalize but returns TextUtils cap mode flags.
     * <p>
     * This can be combined with the app's requested cap modes using bitwise OR.
     *
     * @param textBeforeCursor The text before the cursor position.
     * @param hasSpaceBefore Whether there's a phantom space that will be inserted.
     * @return TextUtils.CAP_MODE_SENTENCES if should capitalize at sentence start, 0 otherwise.
     */
    public static int getSmartCapsMode(final CharSequence textBeforeCursor, final boolean hasSpaceBefore) {
        if (shouldCapitalize(textBeforeCursor, hasSpaceBefore)) {
            return TextUtils.CAP_MODE_SENTENCES;
        }
        return 0;
    }
}
