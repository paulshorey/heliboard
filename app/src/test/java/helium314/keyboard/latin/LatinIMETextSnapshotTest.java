/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package helium314.keyboard.latin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LatinIMETextSnapshotTest {
    @Test
    public void buildFieldTextSnapshot_includesSelectedTextBetweenSides() {
        assertEquals("beforeSELECTafter",
                LatinIME.buildFieldTextSnapshot("before", "SELECT", "after"));
    }

    @Test
    public void buildFieldTextSnapshot_handlesNoSelection() {
        assertEquals("beforeafter",
                LatinIME.buildFieldTextSnapshot("before", null, "after"));
    }

    @Test
    public void getFieldTextSnapshotLength_countsSelection() {
        assertEquals(17,
                LatinIME.getFieldTextSnapshotLength("before", "SELECT", "after"));
    }
}
