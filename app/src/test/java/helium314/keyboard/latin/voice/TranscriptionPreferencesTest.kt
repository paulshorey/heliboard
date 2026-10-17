// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import kotlin.test.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionPreferencesTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("mai-test", Context.MODE_PRIVATE)
    @Before fun reset() { prefs.edit().clear().commit() }
    @Test fun requiresOnlyResourceKeyAndMatchingRegion() {
        val config = TranscriptionPreferences.readMaiConfig(prefs)
        assertEquals("", config.apiKey)
        assertEquals("centralus", config.region)
        assertFalse(config.autoDetectLanguage)
        assertNotNull(config.validationError())
    }
    @Test fun trimsAndPersistsSpeechResourceConfiguration() {
        TranscriptionPreferences.writeMaiApiKey(prefs, " test-key ")
        TranscriptionPreferences.writeMaiRegion(prefs, " SwedenCentral ")
        TranscriptionPreferences.writeMaiAutoDetectLanguage(prefs, true)
        val config = TranscriptionPreferences.readMaiConfig(prefs)
        assertEquals("test-key", config.apiKey)
        assertEquals("swedencentral", config.region)
        assertTrue(config.autoDetectLanguage)
        assertNull(config.validationError())
    }
    @Test fun rejectsUnsupportedRegionsAndMultilineKeys() {
        for (region in listOf("eastus2", "", "centralus/path", "user:pass@host")) {
            assertNotNull(TranscriptionPreferences.MaiConfig("key", region, false).validationError())
        }
        assertNotNull(TranscriptionPreferences.MaiConfig("key\ninjected", "centralus", false).validationError())
        for (region in TranscriptionPreferences.supportedRegions) {
            assertNull(TranscriptionPreferences.MaiConfig("key", region, false).validationError())
        }
    }
    @Test fun absentSilenceSettingUsesOneSecondWithoutSavingAnOverride() {
        assertEquals(1000, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
        assertFalse(prefs.contains(Settings.PREF_VOICE_CHUNK_SILENCE_MS))
    }
    @Test fun savedSecondsConvertOnceAndSubsecondEditsRemainMilliseconds() {
        prefs.edit().putInt("voice_chunk_silence_seconds", 3).commit()
        assertEquals(3000, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
        assertFalse(prefs.contains("voice_chunk_silence_seconds"))
        assertEquals(3000, prefs.getInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS, -1))
        TranscriptionPreferences.writeVoiceChunkSilenceMs(prefs, 750)
        assertEquals(750, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
        assertEquals(750, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
    }
    @Test fun millisecondsTakePrecedenceIfBothPreferenceUnitsExist() {
        prefs.edit().putInt("voice_chunk_silence_seconds", 2)
            .putInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS, 750).commit()
        assertEquals(750, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
        assertFalse(prefs.contains("voice_chunk_silence_seconds"))
    }
    @Test fun storedSilenceDurationsAreBoundedBeforeSecondsConversion() {
        for ((seconds, expected) in listOf(-1 to 1000, Int.MAX_VALUE to 30000)) {
            prefs.edit().clear().putInt("voice_chunk_silence_seconds", seconds).commit()
            assertEquals(expected, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
        }
        for ((milliseconds, expected) in listOf(-1 to 100, Int.MAX_VALUE to 30000, 750 to 750)) {
            TranscriptionPreferences.writeVoiceChunkSilenceMs(prefs, milliseconds)
            assertEquals(expected, TranscriptionPreferences.readVoiceChunkSilenceMs(prefs))
        }
    }
}
