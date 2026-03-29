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
 */
final class EditorWordMirror {
    private final RichInputConnection mConnection;

    private String mMirroredWord = "";
    private boolean mMirroringWord = false;

    EditorWordMirror(@NonNull final RichInputConnection connection) {
        mConnection = connection;
    }

    void reset() {
        mMirroredWord = "";
        mMirroringWord = false;
    }

    void clear() {
        reset();
    }

    boolean isMirroringWord() {
        return mMirroringWord;
    }

    void setMirroredWord(@NonNull final CharSequence word, final boolean mirroring) {
        mMirroredWord = word.toString();
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
            mConnection.deleteTextBeforeCursor(mMirroredWord.length());
        }
        reset();
    }

    private void replaceMirroredWord(@NonNull final CharSequence replacement,
            final boolean keepMirroring) {
        if (mMirroringWord && !TextUtils.isEmpty(mMirroredWord)) {
            mConnection.deleteTextBeforeCursor(mMirroredWord.length());
        }
        mConnection.commitText(replacement, 1);
        mMirroredWord = replacement.toString();
        mMirroringWord = keepMirroring && mMirroredWord.length() > 0;
    }
}
