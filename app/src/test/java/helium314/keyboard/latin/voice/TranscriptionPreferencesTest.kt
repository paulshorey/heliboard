// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionPreferencesTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("mai-test", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun requiresAnEndpointDeploymentAndResourceKey() {
        val config = TranscriptionPreferences.readMaiConfig(prefs)
        assertEquals("", config.apiKey)
        assertEquals("", config.endpoint)
        assertEquals("", config.deployment)
        assertFalse(config.autoDetectLanguage)
        assertNotNull(config.validationError())
    }

    @Test fun trimsAndPersistsResourceConfiguration() {
        TranscriptionPreferences.writeMaiApiKey(prefs, " test-key ")
        TranscriptionPreferences.writeMaiEndpoint(prefs, " https://resource.services.ai.azure.com/ ")
        TranscriptionPreferences.writeMaiDeployment(prefs, " dictation ")
        TranscriptionPreferences.writeMaiAutoDetectLanguage(prefs, true)
        val config = TranscriptionPreferences.readMaiConfig(prefs)
        assertEquals("test-key", config.apiKey)
        assertEquals("https://resource.services.ai.azure.com/", config.endpoint)
        assertEquals("dictation", config.deployment)
        assertTrue(config.autoDetectLanguage)
        assertNull(config.validationError())
    }

    @Test fun rejectsInsecureOrDecoratedResourceUrlsAndMultilineKeys() {
        for (endpoint in listOf("http://resource.azure.com", "https://user:pass@resource.azure.com",
            "https://resource.azure.com/path", "https://resource.azure.com/?api-key=secret",
            "https://resource.azure.com/#fragment", "not a url")) {
            assertNotNull(TranscriptionPreferences.MaiConfig("key", endpoint, "dictation", false).validationError())
        }
        assertNotNull(TranscriptionPreferences.MaiConfig("key\ninjected", "https://resource.azure.com", "dictation", false).validationError())
    }
}
