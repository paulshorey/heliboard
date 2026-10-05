// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
