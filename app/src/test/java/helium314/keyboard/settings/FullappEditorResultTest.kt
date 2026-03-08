// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullappEditorResultTest {
    private val target = FullappEditorResult.TargetSnapshot(
        packageName = "com.example.app",
        fieldId = 42,
        fieldName = "message",
        inputType = 1,
        imeOptions = 2,
        privateImeOptions = "opts"
    )

    @Test
    fun `recent draft syncs only while field still matches original text`() {
        val now = 1_000_000L
        val draft = draft(lastSavedAt = now - 119_999L)

        assertTrue(FullappEditorResult.shouldSyncToCurrentField(draft, "original", false, now))
        assertFalse(FullappEditorResult.shouldSyncToCurrentField(draft, "draft", false, now))
    }

    @Test
    fun `stale draft is kept but not synced`() {
        val now = 1_000_000L
        val draft = draft(lastSavedAt = now - 120_001L)

        assertTrue(FullappEditorResult.matchesCurrentFieldContents(draft, "original"))
        assertFalse(FullappEditorResult.shouldSyncToCurrentField(draft, "original", false, now))
    }

    @Test
    fun `regular editing supersedes saved fullapp draft`() {
        val draft = draft(lastSavedAt = 1_000_000L)

        assertTrue(FullappEditorResult.wasSupersededByRegularEditing(draft, "user kept typing"))
        assertFalse(FullappEditorResult.wasSupersededByRegularEditing(draft, "original"))
        assertFalse(FullappEditorResult.wasSupersededByRegularEditing(draft, "draft"))
    }

    @Test
    fun `draft timestamp must exist to allow sync`() {
        val draft = draft(lastSavedAt = 0L)

        assertFalse(FullappEditorResult.isRecentEnoughToSync(draft, 1_000_000L))
        assertFalse(FullappEditorResult.shouldSyncToCurrentField(draft, "original", false, 1_000_000L))
    }

    @Test
    fun `returning from fullapp bypasses staleness and field-content gating`() {
        val now = 1_000_000L
        val draft = draft(lastSavedAt = now - 999_999L)

        assertTrue(FullappEditorResult.shouldSyncToCurrentField(draft, "something else", true, now))
    }

    private fun draft(lastSavedAt: Long) = FullappEditorResult.DraftRecord(
        target = target,
        originalText = "original",
        draftText = "draft",
        selectionStart = 0,
        selectionEnd = 5,
        lastSavedAt = lastSavedAt
    )
}
