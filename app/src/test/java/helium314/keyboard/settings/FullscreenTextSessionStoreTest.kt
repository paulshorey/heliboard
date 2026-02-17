// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class FullscreenTextSessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetStore() {
        FullscreenTextSessionStore.clearAllSessions(context)
    }

    @Test
    fun storesTextPerPackage() {
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = "com.app.a",
            packageName = "com.app.a",
            text = "alpha",
            state = FullscreenTextSessionStore.STATE_FULLSCREEN_IN_PROGRESS,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "a0",
        )
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = "com.app.b",
            packageName = "com.app.b",
            text = "beta",
            state = FullscreenTextSessionStore.STATE_FULLSCREEN_IN_PROGRESS,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "b0",
        )

        val editorA = EditorInfo().apply { packageName = "com.app.a" }
        val editorB = EditorInfo().apply { packageName = "com.app.b" }
        assertEquals("alpha", FullscreenTextSessionStore.getSessionForEditor(context, editorA, "com.app.a")?.text)
        assertEquals("beta", FullscreenTextSessionStore.getSessionForEditor(context, editorB, "com.app.b")?.text)
    }

    @Test
    fun storesTextPerFieldWhenFingerprintIsStrong() {
        val editor1 = EditorInfo().apply {
            packageName = "com.mail"
            fieldId = 1001
            inputType = 1
            imeOptions = 1
        }
        val editor2 = EditorInfo().apply {
            packageName = "com.mail"
            fieldId = 1002
            inputType = 1
            imeOptions = 1
        }
        val key1 = FullscreenTextSessionStore.buildSessionKey(editor1, editor1.packageName)
        val key2 = FullscreenTextSessionStore.buildSessionKey(editor2, editor2.packageName)

        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = key1,
            packageName = "com.mail",
            text = "field-one",
            state = FullscreenTextSessionStore.STATE_FULLSCREEN_IN_PROGRESS,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "original-one",
        )
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = key2,
            packageName = "com.mail",
            text = "field-two",
            state = FullscreenTextSessionStore.STATE_FULLSCREEN_IN_PROGRESS,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "original-two",
        )

        assertEquals("field-one", FullscreenTextSessionStore.getSessionForEditor(context, editor1, "com.mail")?.text)
        assertEquals("field-two", FullscreenTextSessionStore.getSessionForEditor(context, editor2, "com.mail")?.text)
    }

    @Test
    fun resolveLaunchTextPrefersFullscreenStateOverLiveField() {
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = "com.chat.app",
            packageName = "com.chat.app",
            text = "draft from fullscreen",
            state = FullscreenTextSessionStore.STATE_FULLSCREEN_IN_PROGRESS,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "original app text",
        )

        val resolved = FullscreenTextSessionStore.resolveLaunchText(
            context = context,
            sessionKey = "com.chat.app",
            packageName = "com.chat.app",
            fallbackLiveText = "stale app text",
        )
        assertEquals("draft from fullscreen", resolved)
    }

    @Test
    fun resolveLaunchTextPrefersLiveFieldForRegularState() {
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = "com.docs.app",
            packageName = "com.docs.app",
            text = "possibly stale regular snapshot",
            state = FullscreenTextSessionStore.STATE_REGULAR_ACTIVE,
            lastWriter = FullscreenTextSessionStore.WRITER_REGULAR,
            sourceText = null,
        )

        val resolved = FullscreenTextSessionStore.resolveLaunchText(
            context = context,
            sessionKey = "com.docs.app",
            packageName = "com.docs.app",
            fallbackLiveText = "fresh app text",
        )
        assertEquals("fresh app text", resolved)
    }

    @Test
    fun fallsBackToPackageSessionWhenFieldSpecificMissing() {
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = "com.todo.app",
            packageName = "com.todo.app",
            text = "package draft",
            state = FullscreenTextSessionStore.STATE_PENDING_SYNC,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "old text",
        )
        val editor = EditorInfo().apply {
            packageName = "com.todo.app"
            fieldId = 42
            inputType = 1
            imeOptions = 1
        }
        val snapshot = FullscreenTextSessionStore.getSessionForEditor(context, editor, "com.todo.app")
        assertNotNull(snapshot)
        assertEquals("package draft", snapshot.text)
    }

    @Test
    fun doesNotFallbackToDifferentFieldSpecificSession() {
        val editor1 = EditorInfo().apply {
            packageName = "com.forms.app"
            fieldId = 11
            inputType = 1
            imeOptions = 1
        }
        val editor2 = EditorInfo().apply {
            packageName = "com.forms.app"
            fieldId = 22
            inputType = 1
            imeOptions = 1
        }
        val key1 = FullscreenTextSessionStore.buildSessionKey(editor1, editor1.packageName)
        FullscreenTextSessionStore.upsertSession(
            context = context,
            sessionKey = key1,
            packageName = "com.forms.app",
            text = "field-11 draft",
            state = FullscreenTextSessionStore.STATE_PENDING_SYNC,
            lastWriter = FullscreenTextSessionStore.WRITER_FULLSCREEN,
            sourceText = "src",
        )

        val snapshotForField2 = FullscreenTextSessionStore.getSessionForEditor(
            context,
            editor2,
            "com.forms.app",
        )
        assertEquals(null, snapshotForField2)
    }
}
