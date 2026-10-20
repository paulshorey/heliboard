// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.GrokPreferences
import helium314.keyboard.latin.utils.prefs
import kotlin.test.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 35], application = android.app.Application::class)
class GrokPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { context.prefs().edit().clear().commit(); GrokPreferences.writeApiKey(context, "") }
    @Test fun keyRoundTripsOutsidePreferencesAndClearingDeletesIt() {
        GrokPreferences.writeApiKey(context, " Bearer personal-key ")
        assertEquals("personal-key", GrokPreferences.readApiKey(context))
        assertFalse(context.prefs().all.values.contains("personal-key"))
        assertTrue(context.noBackupFilesDir.resolve("grok-transcription-key").exists())
        GrokPreferences.writeApiKey(context, "")
        assertEquals("", GrokPreferences.readApiKey(context))
        assertFalse(context.noBackupFilesDir.resolve("grok-transcription-key").exists())
    }
    @Test fun comparisonProviderKeysArePreservedAndNeverReused() {
        val prefs = context.prefs()
        prefs.edit().putString("gemini_api_key", "google-key").putString("soniox_api_key", "old-key")
            .putString("gemini_custom_vocabulary", "MyName").commit()
        GrokPreferences.readConfig(prefs)
        assertEquals("", GrokPreferences.readApiKey(context))
        assertEquals("google-key", prefs.getString("gemini_api_key", null))
        assertEquals("old-key", prefs.getString("soniox_api_key", null))
        assertEquals("MyName", GrokPreferences.readVocabulary(prefs))
        prefs.edit().putString(GrokPreferences.VOCABULARY, "").commit()
        assertEquals("", GrokPreferences.readVocabulary(prefs))
    }
    @Test fun interruptedReplacementRecoversTheLastCommittedKey() {
        GrokPreferences.writeApiKey(context, "committed-key")
        val file = context.noBackupFilesDir.resolve("grok-transcription-key")
        // Simulate process death before finishWrite/failWrite.
        AtomicFile(file).startWrite().use { it.write("partial-key".toByteArray()) }
        assertEquals("committed-key", GrokPreferences.readApiKey(context))
        assertEquals("committed-key", file.readText())
    }
    @Test @Config(sdk = [21])
    fun interruptedLegacyReplacementRecoversWhenOnlyTheBackupRemains() {
        GrokPreferences.writeApiKey(context, "committed-key")
        val file = context.noBackupFilesDir.resolve("grok-transcription-key")
        AtomicFile(file).startWrite().close()
        assertTrue(file.delete())
        assertTrue(file.resolveSibling("${file.name}.bak").exists())
        assertEquals("committed-key", GrokPreferences.readApiKey(context))
        assertEquals("committed-key", file.readText())
    }
    @Test fun clearingAnInterruptedReplacementCannotRestoreTheOldKey() {
        GrokPreferences.writeApiKey(context, "committed-key")
        val file = context.noBackupFilesDir.resolve("grok-transcription-key")
        AtomicFile(file).startWrite().use { it.write("partial-key".toByteArray()) }
        GrokPreferences.writeApiKey(context, "")
        assertEquals("", GrokPreferences.readApiKey(context))
        assertFalse(file.exists())
        assertFalse(file.resolveSibling("${file.name}.bak").exists())
        assertFalse(file.resolveSibling("${file.name}.new").exists())
    }
    @Test fun settingsAndVocabularyAreBoundedToProviderLimits() {
        val prefs = context.prefs()
        prefs.edit().putString(GrokPreferences.SMART_TURN, "NaN").putInt(GrokPreferences.TURN_TIMEOUT, 9000).commit()
        assertEquals(0.7, GrokPreferences.readConfig(prefs).smartTurn)
        assertEquals(5000, GrokPreferences.readConfig(prefs).turnTimeoutMs)
        val terms = GrokPreferences.parseVocabulary((0..110).joinToString("\n") { "$it".padEnd(80, 'a') })
        assertEquals(100, terms.size)
        assertTrue(terms.all { it.length == 50 })
        assertFailsWith<IllegalArgumentException> { GrokPreferences.writeApiKey(context, "key\nInjected: header") }
    }
}
