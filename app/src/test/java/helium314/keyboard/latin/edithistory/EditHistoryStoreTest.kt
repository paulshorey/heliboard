// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.edithistory

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.utils.protectedPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditHistoryStoreTest {
    private val target = EditorTargetSnapshot(
        packageName = "com.example.app",
        fieldId = 42,
        fieldName = "message",
        inputType = 1,
        imeOptions = 2,
        privateImeOptions = "opts",
    )

    @Test
    fun `updateLatest collapses repeated updates for the same field`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)

        EditHistoryStore.updateLatest(context, target, "hello", 5, 5)
        EditHistoryStore.updateLatest(context, target, "hello world", 11, 11)

        assertEquals(0, EditHistoryStore.getAllEntries(context).size)
        EditHistoryStore.finalizeLatest(context, target)
        val entries = EditHistoryStore.getAllEntries(context)
        assertEquals(1, entries.size)
        assertEquals("hello world", entries.first().text)
        assertEquals(EditHistorySource.REGULAR, entries.first().source)
    }

    @Test
    fun `finalizeLatest dedups identical consecutive entries`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)

        EditHistoryStore.updateLatest(context, target, "same text", 0, 9)
        EditHistoryStore.finalizeLatest(context, target)
        EditHistoryStore.updateLatest(context, target, "same text", 0, 9)
        EditHistoryStore.finalizeLatest(context, target)

        assertEquals(1, EditHistoryStore.getAllEntries(context).size)
    }

    @Test
    fun `retention drops oldest entries beyond max count`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)
        val now = System.currentTimeMillis()

        repeat(EditHistoryStore.MAX_HISTORY_ENTRIES + 5) { index ->
            val fieldTarget = target.copy(fieldId = index)
            EditHistoryStore.addEntry(
                context = context,
                source = EditHistorySource.REGULAR,
                target = fieldTarget,
                text = "entry-$index",
                selectionStart = 0,
                selectionEnd = 0,
                updatedAt = now - (EditHistoryStore.MAX_HISTORY_ENTRIES + 5 - index) * 1_000L,
            )
        }

        val entries = EditHistoryStore.getAllEntries(context)
        assertTrue(entries.size <= EditHistoryStore.MAX_HISTORY_ENTRIES)
        assertFalse(entries.any { it.text == "entry-0" })
        assertTrue(entries.any { it.text == "entry-${EditHistoryStore.MAX_HISTORY_ENTRIES + 4}" })
    }

    @Test
    fun `oversized entry is tail truncated and flagged`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)
        val oversized = "a".repeat(EditHistoryStore.MAX_ENTRY_CHARS + 25)

        EditHistoryStore.addEntry(
            context = context,
            source = EditHistorySource.REGULAR,
            target = target,
            text = oversized,
            selectionStart = 0,
            selectionEnd = 0,
            updatedAt = System.currentTimeMillis(),
        )

        val entry = EditHistoryStore.getAllEntries(context).single()
        assertTrue(entry.truncated)
        assertEquals(EditHistoryStore.MAX_ENTRY_CHARS, entry.text.length)
        assertTrue(entry.text.endsWith("a".repeat(25)))
    }

    @Test
    fun `getPendingLatestEntries returns in-progress slots`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)

        EditHistoryStore.updateLatest(context, target, "still typing", 5, 5)

        val pending = EditHistoryStore.getPendingLatestEntries(context)
        assertEquals(1, pending.size)
        assertEquals("still typing", pending.first().text)
        assertTrue(EditHistoryStore.getAllEntries(context).isEmpty())
    }

    @Test
    fun `clearAll removes history and latest slots`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)

        EditHistoryStore.updateLatest(context, target, "draft", 1, 1)
        EditHistoryStore.finalizeLatest(context, target)
        assertEquals(1, EditHistoryStore.getAllEntries(context).size)

        EditHistoryStore.clearAll(context)
        assertTrue(EditHistoryStore.getAllEntries(context).isEmpty())
        EditHistoryStore.updateLatest(context, target, "after clear", 1, 1)
        EditHistoryStore.finalizeLatest(context, target)
        assertEquals(1, EditHistoryStore.getAllEntries(context).size)
        assertEquals("after clear", EditHistoryStore.getAllEntries(context).single().text)
    }

    @Test
    fun `legacy fullapp archive migrates into history`() {
        val context = ApplicationProvider.getApplicationContext<App>()
        clearPrefs(context)
        val prefs = context.protectedPrefs()
        val archiveKey = "legacy-archive"
        val legacyJson = """
            {
              "package_name": "com.example.app",
              "field_id": 42,
              "field_name": "message",
              "input_type": 1,
              "ime_options": 2,
              "private_ime_options": "opts",
              "draft_text": "legacy draft",
              "selection_start": 0,
              "selection_end": 11,
              "archived_at": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        prefs.edit()
            .putStringSet("fullapp_archive_keys", setOf(archiveKey))
            .putString("fullapp_archive_$archiveKey", legacyJson)
            .commit()

        val entries = EditHistoryStore.getAllEntries(context)
        assertEquals(1, entries.size)
        assertEquals(EditHistorySource.FULLAPP, entries.first().source)
        assertEquals("legacy draft", entries.first().text)
        assertFalse(prefs.contains("fullapp_archive_keys"))
        assertFalse(prefs.contains("fullapp_archive_$archiveKey"))
    }

    private fun clearPrefs(context: App) {
        context.protectedPrefs().edit().clear().commit()
    }
}
