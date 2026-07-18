// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.edithistory

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.FullappEditorResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FullappEditorResultTest {
    private val sessionToken = "session-1"
    private val target = EditorTargetSnapshot(
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
    fun `regular then fullapp still syncs when field still matches original`() {
        val now = 1_000_000L
        val draft = draft(lastSavedAt = now - 5_000L)

        EditHistoryStore.addEntry(
            context = ApplicationProvider.getApplicationContext(),
            source = EditHistorySource.REGULAR,
            target = target,
            text = "original",
            selectionStart = 0,
            selectionEnd = 8,
            updatedAt = now - 10_000L,
        )

        assertTrue(FullappEditorResult.shouldSyncToCurrentField(draft, "original", false, now))
        assertFalse(FullappEditorResult.wasSupersededByRegularEditing(draft, "original"))
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
        clearPrefs(context)
        val draft = draft(lastSavedAt = System.currentTimeMillis())

        FullappEditorResult.saveDraft(context, draft)
        FullappEditorResult.archiveAndClearDraft(context, draft)

        assertNull(FullappEditorResult.loadDraft(context, target))
        assertTrue(FullappEditorResult.getAllDrafts(context).isEmpty())
        val historyEntries = EditHistoryStore.getAllEntries(context)
        assertEquals(1, historyEntries.size)
        val archived = assertNotNull(historyEntries.firstOrNull())
        assertEquals(EditHistorySource.FULLAPP, archived.source)
        assertEquals(draft.draftText, archived.text)
        assertTrue(archived.updatedAt >= draft.lastSavedAt)
    }

    @Test
    fun `archived drafts are returned newest first`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)
        val older = draft(lastSavedAt = System.currentTimeMillis() - 10_000L)
        val newer = draft(lastSavedAt = System.currentTimeMillis()).copy(
            target = target.copy(fieldId = 43, fieldName = "message2")
        )

        FullappEditorResult.saveDraft(context, older)
        FullappEditorResult.archiveAndClearDraft(context, older)
        Thread.sleep(5)
        FullappEditorResult.saveDraft(context, newer)
        FullappEditorResult.archiveAndClearDraft(context, newer)

        val historyEntries = EditHistoryStore.getAllEntries(context)
        assertEquals(2, historyEntries.size)
        assertEquals(newer.draftText, historyEntries[0].text)
        assertEquals(older.draftText, historyEntries[1].text)
    }

    @Test
    fun `saveDraft evicts oldest live drafts beyond cap`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)
        // Keep age retention from interfering with count-based eviction.
        context.prefs().edit()
            .putInt(Settings.PREF_EDIT_HISTORY_RETENTION_HOURS, Defaults.EDIT_HISTORY_RETENTION_HOURS_NO_LIMIT)
            .commit()
        val now = System.currentTimeMillis()

        repeat(55) { index ->
            val draft = draft(lastSavedAt = now - (55 - index) * 1_000L).copy(
                target = target.copy(fieldId = index, fieldName = "field-$index")
            )
            FullappEditorResult.saveDraft(context, draft)
        }

        val drafts = FullappEditorResult.getAllDrafts(context)
        assertTrue(drafts.size <= 50)
        assertFalse(drafts.any { it.target.fieldId == 0 })
        assertTrue(drafts.any { it.target.fieldId == 54 })
    }

    @Test
    fun `age retention drops live drafts older than configured window`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)
        val now = System.currentTimeMillis()
        val oldDraft = draft(lastSavedAt = now - 25L * 60L * 60L * 1000L).copy(
            target = target.copy(fieldId = 1, fieldName = "old")
        )
        val recentDraft = draft(lastSavedAt = now - 60L * 60L * 1000L).copy(
            target = target.copy(fieldId = 2, fieldName = "recent")
        )

        FullappEditorResult.saveDraft(context, oldDraft)
        FullappEditorResult.saveDraft(context, recentDraft)

        val drafts = FullappEditorResult.getAllDrafts(context)
        assertEquals(1, drafts.size)
        assertEquals(2, drafts.single().target.fieldId)
    }

    private fun clearPrefs(context: App) {
        context.protectedPrefs().edit().clear().commit()
        context.prefs().edit().clear().commit()
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
