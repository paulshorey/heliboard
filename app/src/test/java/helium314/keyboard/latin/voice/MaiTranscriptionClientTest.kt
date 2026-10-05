// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaiTranscriptionClientTest {
    @Test fun usesTheDocumentedResourcePathAndTranscriptionIntent() {
        assertEquals("wss://resource.services.ai.azure.com/mai/v1/realtime?intent=transcription",
            MaiTranscriptionClient.buildStreamingUrl("https://resource.services.ai.azure.com/"))
        assertEquals("wss://resource.services.ai.azure.com/mai/v1/realtime?intent=transcription",
            MaiTranscriptionClient.buildStreamingUrl("wss://resource.services.ai.azure.com"))
    }

    @Test fun configuresPcmAndDeploymentWithExplicitNullVadAndNoiseReduction() {
        val json = JSONObject(MaiTranscriptionClient.buildSessionUpdate("my-dictation", "en"))
        assertEquals("session.update", json.getString("type"))
        val session = json.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(16000, input.getJSONObject("format").getInt("rate"))
        assertEquals("my-dictation", input.getJSONObject("transcription").getString("model"))
        assertEquals("en", input.getJSONObject("transcription").getString("language"))
        assertTrue(input.has("turn_detection") && input.isNull("turn_detection"))
        assertTrue(input.has("noise_reduction") && input.isNull("noise_reduction"))
    }

    @Test fun usesBareLanguageHintsAndNullForAutomaticDetection() {
        assertEquals("en", MaiTranscriptionClient.resolveLanguage("en_US", false))
        assertEquals("pt", MaiTranscriptionClient.resolveLanguage("pt-BR", false))
        assertEquals("yue", MaiTranscriptionClient.resolveLanguage("yue-Hant-HK", false))
        assertNull(MaiTranscriptionClient.resolveLanguage("en-US", true))
        assertNull(MaiTranscriptionClient.resolveLanguage("und", false))
        assertTrue(JSONObject(MaiTranscriptionClient.buildSessionUpdate("dictation", null))
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input")
            .getJSONObject("transcription").isNull("language"))
    }
}
