package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionPreferencesTest {

    @Test
    fun readGeminiApiKey_clearsPreviousProviderKeysWithoutCopyingThem() {
        val prefs = newPrefs()
        prefs.edit()
            .putString("speechmatics_api_key", "speechmatics-key")
            .putString("deepgram_api_key", "deepgram-key")
            .putString("soniox_api_key", "soniox-key")
            .commit()

        assertEquals("", TranscriptionPreferences.readGeminiApiKey(prefs))
        assertFalse(prefs.contains("speechmatics_api_key"))
        assertFalse(prefs.contains("deepgram_api_key"))
        assertFalse(prefs.contains("soniox_api_key"))
    }

    @Test
    fun readGeminiApiKey_preservesGeminiKeyWhenClearingPreviousProviderKeys() {
        val prefs = newPrefs()
        prefs.edit()
            .putString(Settings.PREF_GEMINI_API_KEY, "gemini-key")
            .putString("soniox_api_key", "soniox-key")
            .commit()

        assertEquals("gemini-key", TranscriptionPreferences.readGeminiApiKey(prefs))
        assertFalse(prefs.contains("soniox_api_key"))
    }

    @Test
    fun migrateLegacyProviderPrefs_movesPreviousCustomTermsIntoGeminiVocabulary() {
        val prefs = newPrefs()
        prefs.edit().putString("soniox_custom_terms", "Acme\nBudapest\n").commit()

        TranscriptionPreferences.migrateLegacyProviderPrefs(prefs)

        assertEquals(
            listOf("Acme", "Budapest"),
            TranscriptionPreferences.readGeminiCustomVocabulary(prefs)
        )
        assertFalse(prefs.contains("soniox_custom_terms"))
    }

    @Test
    fun migrateLegacyProviderPrefs_keepsExistingGeminiVocabulary() {
        val prefs = newPrefs()
        prefs.edit()
            .putString("soniox_custom_terms", "Acme")
            .putString(Settings.PREF_GEMINI_CUSTOM_VOCABULARY, "Zeppelin")
            .commit()

        TranscriptionPreferences.migrateLegacyProviderPrefs(prefs)

        assertEquals(
            listOf("Zeppelin"),
            TranscriptionPreferences.readGeminiCustomVocabulary(prefs)
        )
    }

    @Test
    fun defaults_preferAccuracyOverLatency() {
        val prefs = newPrefs()
        val config = TranscriptionPreferences.readGeminiConfig(prefs)

        // Smart mode is what applies punctuation, casing and self-correction cleanup.
        assertEquals(
            GeminiTranscriptionClient.TRANSCRIPTION_MODE_SMART,
            config.transcriptionMode
        )
        // An explicit language hint beats auto-detection on short dictations.
        assertFalse(config.autoDetectLanguage)
        assertTrue(config.useEditorContext)
        assertEquals(
            Defaults.PREF_GEMINI_END_OF_SPEECH_SILENCE_MS,
            config.endOfSpeechSilenceMs
        )
        // The local finalize backstop must not fire before the server's own
        // end-of-speech window, or utterances get chopped in half.
        assertTrue(
            Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS * 1000 >
                Defaults.PREF_GEMINI_END_OF_SPEECH_SILENCE_MS
        )
    }

    @Test
    fun sanitizeEndOfSpeechSilenceMs_clampsToAccuracySafeBounds() {
        assertEquals(
            GeminiTranscriptionClient.MIN_END_OF_SPEECH_SILENCE_MS,
            TranscriptionPreferences.sanitizeEndOfSpeechSilenceMs(50)
        )
        assertEquals(
            GeminiTranscriptionClient.MAX_END_OF_SPEECH_SILENCE_MS,
            TranscriptionPreferences.sanitizeEndOfSpeechSilenceMs(99_999)
        )
    }

    @Test
    fun transcriptionMode_roundTripsAndRejectsUnknownValues() {
        val prefs = newPrefs()
        TranscriptionPreferences.writeGeminiSmartMode(prefs, false)
        assertEquals(
            GeminiTranscriptionClient.TRANSCRIPTION_MODE_VERBATIM,
            TranscriptionPreferences.readGeminiTranscriptionMode(prefs)
        )
        assertFalse(TranscriptionPreferences.readGeminiSmartMode(prefs))

        TranscriptionPreferences.writeGeminiTranscriptionMode(prefs, "nonsense")
        assertEquals(
            GeminiTranscriptionClient.TRANSCRIPTION_MODE_SMART,
            TranscriptionPreferences.readGeminiTranscriptionMode(prefs)
        )
    }

    @Test
    fun readGeminiCustomVocabulary_returnsEmptyByDefault() {
        val prefs = newPrefs()
        assertEquals(emptyList(), TranscriptionPreferences.readGeminiCustomVocabulary(prefs))
    }

    @Test
    fun parseCustomVocabulary_trimsBlankAndDuplicateEntries() {
        val raw = """
            Kubernetes
            
            kubernetes
            MyProject
              MyProject  
            
        """.trimIndent()
        val parsed = TranscriptionPreferences.parseCustomVocabulary(raw)
        // Dedup is case-sensitive on purpose — Gemini uses casing as a hint.
        assertEquals(listOf("Kubernetes", "kubernetes", "MyProject"), parsed)
    }

    @Test
    fun writeAndReadCustomVocabulary_roundTripsRawAndParsed() {
        val prefs = newPrefs()
        TranscriptionPreferences.writeGeminiCustomVocabulary(prefs, "Foo\nBar\nFoo\n")
        assertEquals("Foo\nBar\nFoo\n", TranscriptionPreferences.readGeminiCustomVocabularyRaw(prefs))
        assertEquals(listOf("Foo", "Bar"), TranscriptionPreferences.readGeminiCustomVocabulary(prefs))
        assertEquals(
            listOf("Foo", "Bar"),
            TranscriptionPreferences.readGeminiConfig(prefs).customVocabulary
        )
    }

    private fun newPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(
            "transcription_preferences_test_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
}
