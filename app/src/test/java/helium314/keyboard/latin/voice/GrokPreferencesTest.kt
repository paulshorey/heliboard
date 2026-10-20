// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.GrokPreferences
import helium314.keyboard.latin.utils.prefs
import kotlin.test.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
