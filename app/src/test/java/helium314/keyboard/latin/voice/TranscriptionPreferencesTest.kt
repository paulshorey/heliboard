package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.prefs
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class TranscriptionPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.prefs()
    @Before fun reset() { prefs.edit().clear().commit(); TranscriptionPreferences.writeMuseApiKey(context, "") }
    @Test fun personalKeyLivesOutsideBackedUpPreferencesAndCanBeCleared() {
        TranscriptionPreferences.writeMuseApiKey(context, " Bearer personal-test-key ")
        assertEquals("personal-test-key", TranscriptionPreferences.readMuseApiKey(context))
        assertTrue(prefs.all.isEmpty())
        assertTrue(java.io.File(context.noBackupFilesDir, "muse-api-key").isFile)
        TranscriptionPreferences.writeMuseApiKey(context, "")
        assertEquals("", TranscriptionPreferences.readMuseApiKey(context))
    }
    @Test fun comparisonKeysSurviveVocabularyMigrationAndMuseKeyUpdates() {
        prefs.edit().putString("gemini_api_key", "old-key").putString("mai_api_key", "azure-key")
            .putString("gemini_custom_vocabulary", "Anaya\nGitHub").commit()
        assertEquals(listOf("Anaya", "GitHub"), TranscriptionPreferences.readMuseConfig(prefs).customVocabulary)
        TranscriptionPreferences.writeMuseApiKey(context, "new-key")
        assertEquals("old-key", prefs.getString("gemini_api_key", ""))
        assertEquals("azure-key", prefs.getString("mai_api_key", ""))
        TranscriptionPreferences.writeMuseCustomVocabulary(prefs, "")
        assertEquals(emptyList(), TranscriptionPreferences.readMuseConfig(prefs).customVocabulary)
    }
    @Test fun defaultsBiasLanguageAndEditorWordsAndVocabularyIsBounded() {
        val config = TranscriptionPreferences.readMuseConfig(prefs)
        assertFalse(config.autoDetectLanguage)
        assertTrue(config.useEditorContext)
        assertEquals(listOf("Anaya", "API"), TranscriptionPreferences.parseCustomVocabulary(" Anaya\nanaya\nAPI\n\n"))
        assertEquals(100, TranscriptionPreferences.parseCustomVocabulary((1..150).joinToString("\n") { "Name$it" }).size)
    }
    @Test fun retiredExperimentalFlagsDoNotChangeConfigOrCredentials() {
        TranscriptionPreferences.writeMuseApiKey(context, "key")
        val original = TranscriptionPreferences.readMuseConfig(prefs)
        prefs.edit().putBoolean("muse_responsive_dictation", true)
            .putBoolean("muse_timing_diagnostics", true).commit()
        assertEquals(original, TranscriptionPreferences.readMuseConfig(prefs))
        assertEquals("key", TranscriptionPreferences.readMuseApiKey(context))
    }

}
