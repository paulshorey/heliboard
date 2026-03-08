// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.protectedPrefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class FullappEditorResultTest {
    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.protectedPrefs().edit().clear().commit()
    }

    @Test
    fun `findDraftForEditor skips stale draft when field text changed`() {
        val target = targetSnapshot()
        FullappEditorResult.saveDraft(context, draftRecord(target, originalText = "before", draftText = "from fullapp"))

        val matchedDraft = FullappEditorResult.findDraftForEditor(
            context = context,
            editorInfo = editorInfoFor(target),
            currentFieldText = "typed later in regular mode"
        )

        assertNull(matchedDraft)
    }

    @Test
    fun `reconcileDraftWithCurrentField keeps unsynced fullapp draft while app field unchanged`() {
        val target = targetSnapshot()
        val existingDraft = draftRecord(target, originalText = "before", draftText = "from fullapp", selectionEnd = 4)
        FullappEditorResult.saveDraft(context, existingDraft)

        val reconciledDraft = FullappEditorResult.reconcileDraftWithCurrentField(
            context = context,
            target = target,
            currentFieldText = "before",
            selectionStart = 2,
            selectionEnd = 2
        )

        assertEquals(existingDraft, reconciledDraft)
        assertEquals(existingDraft, FullappEditorResult.loadDraft(context, target))
    }

    @Test
    fun `reconcileDraftWithCurrentField replaces stale draft with latest regular text`() {
        val target = targetSnapshot()
        FullappEditorResult.saveDraft(context, draftRecord(target, originalText = "before", draftText = "from fullapp"))

        val reconciledDraft = FullappEditorResult.reconcileDraftWithCurrentField(
            context = context,
            target = target,
            currentFieldText = "typed later in regular mode",
            selectionStart = 6,
            selectionEnd = 6
        )

        val savedDraft = FullappEditorResult.loadDraft(context, target)
        assertNotNull(reconciledDraft)
        assertNotNull(savedDraft)
        assertEquals("typed later in regular mode", reconciledDraft.originalText)
        assertEquals("typed later in regular mode", reconciledDraft.draftText)
        assertEquals(6, reconciledDraft.selectionStart)
        assertEquals(6, reconciledDraft.selectionEnd)
        assertEquals(reconciledDraft, savedDraft)
    }

    @Test
    fun `reconcileDraftWithCurrentField normalizes synced text so old original stops mattering`() {
        val target = targetSnapshot()
        FullappEditorResult.saveDraft(context, draftRecord(target, originalText = "", draftText = "latest from fullapp"))

        val reconciledDraft = FullappEditorResult.reconcileDraftWithCurrentField(
            context = context,
            target = target,
            currentFieldText = "latest from fullapp",
            selectionStart = 3,
            selectionEnd = 3
        )

        assertNotNull(reconciledDraft)
        assertEquals("latest from fullapp", reconciledDraft.originalText)
        assertEquals("latest from fullapp", reconciledDraft.draftText)
        assertEquals(3, reconciledDraft.selectionStart)
        assertEquals(3, reconciledDraft.selectionEnd)
    }

    private fun targetSnapshot() = FullappEditorResult.TargetSnapshot(
        packageName = "com.example.target",
        fieldId = 42,
        fieldName = "body",
        inputType = 1,
        imeOptions = 2,
        privateImeOptions = "opts"
    )

    private fun editorInfoFor(target: FullappEditorResult.TargetSnapshot) = EditorInfo().apply {
        packageName = target.packageName
        fieldId = target.fieldId
        fieldName = target.fieldName
        inputType = target.inputType
        imeOptions = target.imeOptions
        privateImeOptions = target.privateImeOptions
    }

    private fun draftRecord(
        target: FullappEditorResult.TargetSnapshot,
        originalText: String,
        draftText: String,
        selectionStart: Int = draftText.length,
        selectionEnd: Int = draftText.length
    ) = FullappEditorResult.DraftRecord(
        target = target,
        originalText = originalText,
        draftText = draftText,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        lastSavedAt = 1234L
    )
}
