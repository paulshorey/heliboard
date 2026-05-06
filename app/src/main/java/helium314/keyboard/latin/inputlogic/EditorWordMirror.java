/*
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.inputlogic;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import helium314.keyboard.latin.RichInputConnection;

/**
 * Mirrors the keyboard-owned in-progress word into the host editor using committed replace/delete
 * operations only.
 *
 * <p>The host editor should be treated as a plain text sink. Current-word state stays inside the
 * keyboard ({@code WordComposer}); this helper just keeps the host text in sync.
 *
 * <p>The mirror tracks the caret position inside the mirrored word as a count of java chars
 * before/after the caret. For ordinary typing the caret always sits at the end of the word, so
 * {@code mCharsAfterCursor} is zero. When suggestions are resumed on a word that the user has
 * tapped into, the caret may be in the middle of the word; in that case both halves must be
 * deleted when the word is replaced or cleared, otherwise we would chew through whitespace and
 * the previous word and leave the tail of the focused word untouched.
 */
final class EditorWordMirror {
    private final RichInputConnection mConnection;

    private String mMirroredWord = "";
    private int mCharsAfterCursor = 0;
    private boolean mMirroringWord = false;

    EditorWordMirror(@NonNull final RichInputConnection connection) {
        mConnection = connection;
    }

    void reset() {
        mMirroredWord = "";
        mCharsAfterCursor = 0;
        mMirroringWord = false;
    }

    void clear() {
        reset();
    }

    boolean isMirroringWord() {
        return mMirroringWord;
    }

    /**
     * Arms the mirror with the word that already exists in the host editor at the cursor.
     *
     * @param word the entire word currently shown in the host editor.
     * @param charsAfterCursor number of java chars of {@code word} that follow the caret.
     * @param mirroring whether the mirror should treat itself as the live owner of the word.
     */
    void setMirroredWord(@NonNull final CharSequence word, final int charsAfterCursor,
            final boolean mirroring) {
        mMirroredWord = word.toString();
        final int clampedAfter = Math.max(0, Math.min(charsAfterCursor, mMirroredWord.length()));
        mCharsAfterCursor = clampedAfter;
        mMirroringWord = mirroring && mMirroredWord.length() > 0;
    }

    void mirrorWord(@NonNull final CharSequence word) {
        replaceMirroredWord(word, true);
    }

    void commitMirroredWord(@NonNull final CharSequence replacement) {
        replaceMirroredWord(replacement, false);
    }

    void clearMirroredWord() {
        if (mMirroringWord && !TextUtils.isEmpty(mMirroredWord)) {
            deleteMirroredWordFromEditor();
        }
        reset();
    }

    private void replaceMirroredWord(@NonNull final CharSequence replacement,
            final boolean keepMirroring) {
        if (mMirroringWord && !TextUtils.isEmpty(mMirroredWord)) {
            deleteMirroredWordFromEditor();
        }
        mConnection.commitText(replacement, 1);
        mMirroredWord = replacement.toString();
        // After commitText the caret sits at the end of the inserted text, so there is nothing
        // to the right of it that belongs to the mirrored word.
        mCharsAfterCursor = 0;
        mMirroringWord = keepMirroring && mMirroredWord.length() > 0;
    }

    private void deleteMirroredWordFromEditor() {
        // Delete the trailing portion of the word first so the caret remains stable while we
        // remove the leading portion. If the caret happens to sit at the end of the word
        // (the typical typing case), mCharsAfterCursor is 0 and only the before-cursor delete
        // runs.
        if (mCharsAfterCursor > 0) {
            mConnection.deleteTextAfterCursor(mCharsAfterCursor);
        }
        final int before = mMirroredWord.length() - mCharsAfterCursor;
        if (before > 0) {
            mConnection.deleteTextBeforeCursor(before);
        }
    }
}
