/*
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class FullappTextSnapshotUtils {
    private FullappTextSnapshotUtils() {
        // Utility class.
    }

    @NonNull
    static String buildFieldTextSnapshot(@Nullable final CharSequence before,
                                         @Nullable final CharSequence selected,
                                         @Nullable final CharSequence after) {
        if (before == null && selected == null && after == null) {
            return "";
        }
        final StringBuilder snapshot = new StringBuilder();
        if (before != null) {
            snapshot.append(before);
        }
        if (selected != null) {
            snapshot.append(selected);
        }
        if (after != null) {
            snapshot.append(after);
        }
        return snapshot.toString();
    }

    static int getFieldTextSnapshotLength(@Nullable final CharSequence before,
                                          @Nullable final CharSequence selected,
                                          @Nullable final CharSequence after) {
        int totalLength = 0;
        if (before != null) {
            totalLength += before.length();
        }
        if (selected != null) {
            totalLength += selected.length();
        }
        if (after != null) {
            totalLength += after.length();
        }
        return totalLength;
    }
}
