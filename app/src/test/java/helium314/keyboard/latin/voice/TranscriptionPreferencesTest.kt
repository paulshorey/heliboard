package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionPreferencesTest {

    @Test
    fun readSonioxApiKey_clearsLegacyProviderKeysWithoutCopyingThem() {
        val prefs = newPrefs()
        prefs.edit()
            .putString("speechmatics_api_key", "speechmatics-key")
            .putString("deepgram_api_key", "deepgram-key")
            .commit()

        assertEquals("", TranscriptionPreferences.readSonioxApiKey(prefs))
        assertFalse(prefs.contains("speechmatics_api_key"))
        assertFalse(prefs.contains("deepgram_api_key"))
    }

    @Test
    fun readSonioxApiKey_preservesExistingSonioxKeyWhenClearingLegacyKeys() {
        val prefs = newPrefs()
        prefs.edit()
            .putString(Settings.PREF_SONIOX_API_KEY, "soniox-key")
            .putString("speechmatics_api_key", "speechmatics-key")
            .commit()

        assertEquals("soniox-key", TranscriptionPreferences.readSonioxApiKey(prefs))
        assertFalse(prefs.contains("speechmatics_api_key"))
    }

    @Test
    fun readSonioxCustomTerms_returnsEmptyByDefault() {
        val prefs = newPrefs()
        assertEquals(emptyList(), TranscriptionPreferences.readSonioxCustomTerms(prefs))
    }

    @Test
    fun parseCustomTerms_trimsBlankAndDuplicateEntries() {
        val raw = """
            Kubernetes
            
            kubernetes
            MyProject
              MyProject  
            
        """.trimIndent()
        val parsed = TranscriptionPreferences.parseCustomTerms(raw)
        // Trim/dedup is case-sensitive on purpose — Soniox uses casing as a hint.
        assertEquals(listOf("Kubernetes", "kubernetes", "MyProject"), parsed)
    }

    @Test
    fun writeAndReadSonioxCustomTerms_roundTripsRawAndParsed() {
        val prefs = newPrefs()
        TranscriptionPreferences.writeSonioxCustomTerms(prefs, "Foo\nBar\nFoo\n")
        assertEquals("Foo\nBar\nFoo\n", TranscriptionPreferences.readSonioxCustomTermsRaw(prefs))
        assertEquals(listOf("Foo", "Bar"), TranscriptionPreferences.readSonioxCustomTerms(prefs))
        assertEquals(listOf("Foo", "Bar"), TranscriptionPreferences.readSonioxConfig(prefs).customTerms)
    }

    private fun newPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(
            "transcription_preferences_test_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
}
