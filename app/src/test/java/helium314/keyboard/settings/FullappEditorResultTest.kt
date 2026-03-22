// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FullappEditorResultTest {
    private val sessionToken = "session-1"
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

    @Test
    fun `draft only restores for matching launch session`() {
        val draft = draft(lastSavedAt = 1_000_000L)

        assertTrue(FullappEditorResult.belongsToLaunchSession(draft, sessionToken))
        assertFalse(FullappEditorResult.belongsToLaunchSession(draft, "session-2"))
        assertFalse(FullappEditorResult.belongsToLaunchSession(draft, ""))
    }

    @Test
    fun `archive moves draft out of live list and keeps text in history`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.protectedPrefs().edit().clear().commit()
        val draft = draft(lastSavedAt = 1_000_000L)

        FullappEditorResult.saveDraft(context, draft)
        FullappEditorResult.archiveAndClearDraft(context, draft)

        assertNull(FullappEditorResult.loadDraft(context, target))
        assertTrue(FullappEditorResult.getAllDrafts(context).isEmpty())
        val archivedDrafts = FullappEditorResult.getAllArchivedDrafts(context)
        assertEquals(1, archivedDrafts.size)
        val archived = assertNotNull(archivedDrafts.firstOrNull())
        assertEquals(draft, archived.draft)
        assertTrue(archived.archivedAt >= draft.lastSavedAt)
    }

    @Test
    fun `archived drafts are returned newest first`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        context.protectedPrefs().edit().clear().commit()
        val older = draft(lastSavedAt = 100L)
        val newer = draft(lastSavedAt = 200L).copy(
            target = target.copy(fieldId = 43, fieldName = "message2")
        )

        FullappEditorResult.saveDraft(context, older)
        FullappEditorResult.archiveAndClearDraft(context, older)
        Thread.sleep(5)
        FullappEditorResult.saveDraft(context, newer)
        FullappEditorResult.archiveAndClearDraft(context, newer)

        val archivedDrafts = FullappEditorResult.getAllArchivedDrafts(context)
        assertEquals(2, archivedDrafts.size)
        assertEquals(newer, archivedDrafts[0].draft)
        assertEquals(older, archivedDrafts[1].draft)
    }

    private fun draft(lastSavedAt: Long) = FullappEditorResult.DraftRecord(
        target = target,
        originalText = "original",
        draftText = "draft",
        selectionStart = 0,
        selectionEnd = 5,
        launchSessionToken = sessionToken,
        lastSavedAt = lastSavedAt
    )
}
