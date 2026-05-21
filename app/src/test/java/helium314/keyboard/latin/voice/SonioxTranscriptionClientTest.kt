package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SonioxTranscriptionClientTest {

    @Test
    fun buildStartConfigMessage_setsRawPcmConfigAndModel() {
        val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en-US",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false
        )
        val payload = JSONObject(
            SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
        )

        assertEquals("API_KEY", payload.getString("api_key"))
        assertEquals("stt-rt-v4", payload.getString("model"))
        assertEquals("pcm_s16le", payload.getString("audio_format"))
        assertEquals(VoiceRecorder.SAMPLE_RATE, payload.getInt("sample_rate"))
        assertEquals(1, payload.getInt("num_channels"))
        assertTrue(payload.getBoolean("enable_endpoint_detection"))
        assertEquals(2000, payload.getInt("max_endpoint_delay_ms"))
        assertFalse(payload.getBoolean("enable_speaker_diarization"))

        val hints = payload.getJSONArray("language_hints")
        assertEquals(1, hints.length())
        assertEquals("en", hints.getString(0))

        val terms = payload.getJSONObject("context").getJSONArray("terms")
        assertTrue((0 until terms.length()).any { terms.getString(it) == "HeliBoard" })
        assertTrue((0 until terms.length()).any { terms.getString(it) == "Soniox" })
    }

    @Test
    fun buildStartConfigMessage_omitsContextWhenNoTermsProvided() {
        val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en-US",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false,
            contextTerms = emptyList()
        )
        val payload = JSONObject(
            SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
        )

        assertFalse(payload.has("context"))
    }

    @Test
    fun buildStartConfigMessage_clampsMaxEndpointDelayWithinAllowedRange() {
        val tooSmall = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 100,
            diarizationEnabled = false
        )
        val tooLarge = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 9999,
            diarizationEnabled = false
        )
        assertEquals(SonioxTranscriptionClient.MIN_MAX_ENDPOINT_DELAY_MS, tooSmall.maxEndpointDelayMs)
        assertEquals(SonioxTranscriptionClient.MAX_MAX_ENDPOINT_DELAY_MS, tooLarge.maxEndpointDelayMs)
    }

    @Test
    fun buildStartConfigMessage_diarizationEnabled() {
        val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "fr",
            enableEndpointDetection = false,
            maxEndpointDelayMs = 1500,
            diarizationEnabled = true
        )
        val payload = JSONObject(
            SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
        )
        assertTrue(payload.getBoolean("enable_speaker_diarization"))
        assertFalse(payload.getBoolean("enable_endpoint_detection"))
        assertEquals(1500, payload.getInt("max_endpoint_delay_ms"))
        assertEquals("fr", payload.getJSONArray("language_hints").getString(0))
    }

    @Test
    fun buildStartConfigMessage_omitsLanguageHintsWhenLanguageIsBlankOrUnknown() {
        for (tag in listOf<String?>(null, "", "und", "zz")) {
            val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
                languageTag = tag,
                enableEndpointDetection = true,
                maxEndpointDelayMs = 2000,
                diarizationEnabled = false
            )
            val payload = JSONObject(
                SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
            )
            assertFalse(payload.has("language_hints"), "tag=$tag should omit language_hints")
        }
    }

    @Test
    fun buildSegmentFromFinalTokens_concatenatesFinalTokensAndTrims() {
        val tokens = JSONArray()
            .put(finalToken("Hello"))
            .put(finalToken(" world"))
            .put(finalToken("."))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )

        val segment = assertNotNull(result.segment)
        assertEquals("Hello world.", segment.text)
        assertFalse(segment.attachesToPrevious)
    }


    @Test
    fun buildSegmentFromFinalTokens_attachesWhenSegmentStartsWithSubwordContinuation() {
        val tokens = JSONArray()
            .put(finalToken("ing"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )
        val segment = assertNotNull(result.segment)
        assertEquals("ing", segment.text)
        assertTrue(segment.attachesToPrevious)
    }

    @Test
    fun buildSegmentFromFinalTokens_attachesWhenSegmentStartsWithPunctuation() {
        val tokens = JSONArray()
            .put(finalToken(", thanks"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )
        val segment = assertNotNull(result.segment)
        assertEquals(", thanks", segment.text)
        assertTrue(segment.attachesToPrevious)
    }

    @Test
    fun buildSegmentFromFinalTokens_skipsNonFinalTokens() {
        val tokens = JSONArray()
            .put(finalToken("Hello"))
            .put(token(" partial", isFinal = false))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )
        val segment = assertNotNull(result.segment)
        assertEquals("Hello", segment.text)
    }

    @Test
    fun buildSegmentFromFinalTokens_skipsManualFinalizeMarker() {
        val tokens = JSONArray()
            .put(finalToken("Hello"))
            .put(finalToken("<fin>"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )
        val segment = assertNotNull(result.segment)
        assertEquals("Hello", segment.text)
    }

    @Test
    fun buildSegmentFromFinalTokens_skipsEndpointMarker() {
        // Soniox emits "<end>" as a final token whenever endpoint detection
        // fires; raw WebSocket consumers must filter it manually or it leaks
        // into the user's editor.
        val tokens = JSONArray()
            .put(finalToken("Hello world"))
            .put(finalToken("."))
            .put(finalToken("<end>"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )
        val segment = assertNotNull(result.segment)
        assertEquals("Hello world.", segment.text)
    }

    @Test
    fun buildStartConfigMessage_includesContextTextWhenProvided() {
        val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false,
            contextText = "Earlier in the document we discussed the migration plan."
        )
        val payload = JSONObject(
            SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
        )
        val context = payload.getJSONObject("context")
        assertEquals(
            "Earlier in the document we discussed the migration plan.",
            context.getString("text")
        )
        assertTrue(context.has("terms"), "default terms should still be present")
    }

    @Test
    fun buildStartConfigMessage_omitsContextTextWhenBlank() {
        for (raw in listOf<String?>(null, "", "   ", "\n\n  \t")) {
            val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
                languageTag = "en",
                enableEndpointDetection = true,
                maxEndpointDelayMs = 2000,
                diarizationEnabled = false,
                contextText = raw
            )
            val payload = JSONObject(
                SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
            )
            val context = payload.optJSONObject("context")
            assertTrue(
                context == null || !context.has("text"),
                "raw=$raw should omit context.text"
            )
        }
    }

    @Test
    fun buildStartConfigMessage_truncatesOverlongContextText() {
        val long = "x".repeat(SonioxTranscriptionClient.MAX_CONTEXT_TEXT_CHARS + 250)
        val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false,
            contextText = long
        )
        val payload = JSONObject(
            SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
        )
        val sentText = payload.getJSONObject("context").getString("text")
        assertEquals(SonioxTranscriptionClient.MAX_CONTEXT_TEXT_CHARS, sentText.length)
    }

    @Test
    fun buildStartConfigMessage_mergesBuiltinAndCustomTermsDeduped() {
        val sessionConfig = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false,
            // Includes a duplicate "API" already in the defaults plus blank/whitespace entries.
            customContextTerms = listOf("Kubernetes", "MyProject", "API", "  ", "")
        )
        val payload = JSONObject(
            SonioxTranscriptionClient.buildStartConfigMessage("API_KEY", sessionConfig)
        )
        val terms = payload.getJSONObject("context").getJSONArray("terms")
        val termsList = (0 until terms.length()).map { terms.getString(it) }
        assertTrue(termsList.contains("MyProject"), "custom term should be present: $termsList")
        assertEquals(termsList.toSet().size, termsList.size, "terms must be deduped: $termsList")
        assertFalse(termsList.contains(""), "blank terms must be filtered: $termsList")
    }

    @Test
    fun buildSegmentFromFinalTokens_returnsNullForEmptyOrAllNonFinalTokens() {
        val empty = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = JSONArray(),
            primarySpeaker = null,
            diarizationEnabled = false
        )
        assertNull(empty.segment)

        val onlyNonFinal = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = JSONArray().put(token("partial", isFinal = false)),
            primarySpeaker = null,
            diarizationEnabled = false
        )
        assertNull(onlyNonFinal.segment)
    }

    @Test
    fun buildSegmentFromFinalTokens_locksOnFirstSpeakerWhenDiarizationEnabled() {
        val tokens = JSONArray()
            .put(finalToken("Primary speaker", speaker = "1"))
            .put(finalToken(" extra", speaker = "1"))
            .put(finalToken(" interloper", speaker = "2"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = true
        )
        val segment = assertNotNull(result.segment)
        assertEquals("Primary speaker extra", segment.text)
        assertEquals("1", result.observedSpeaker)
    }

    @Test
    fun buildSegmentFromFinalTokens_keepsLockedSpeakerAcrossResponses() {
        val tokens = JSONArray()
            .put(finalToken("primary text", speaker = "1"))
            .put(finalToken(" interloper", speaker = "2"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = "1",
            diarizationEnabled = true
        )
        val segment = assertNotNull(result.segment)
        assertEquals("primary text", segment.text)
    }

    @Test
    fun buildErrorDescription_combinesCodeAndMessage() {
        val payload = JSONObject()
            .put("error_code", 401)
            .put("error_type", "unauthenticated")
            .put("error_message", "Incorrect API key")
            .put("request_id", "req_123")
        val description = SonioxTranscriptionClient.buildErrorDescription(payload)
        assertEquals("unauthenticated 401: Incorrect API key (request_id=req_123)", description)
    }

    @Test
    fun buildErrorDescription_handlesPartialFields() {
        val onlyMessage = JSONObject().put("error_message", "boom")
        val onlyCode = JSONObject().put("error_code", "RATE_LIMITED")
        val empty = JSONObject()

        assertEquals("boom", SonioxTranscriptionClient.buildErrorDescription(onlyMessage))
        assertEquals("RATE_LIMITED", SonioxTranscriptionClient.buildErrorDescription(onlyCode))
        assertEquals(
            "Soniox reported an unknown error",
            SonioxTranscriptionClient.buildErrorDescription(empty)
        )
    }

    private fun finalToken(text: String, speaker: String? = null): JSONObject {
        return token(text = text, isFinal = true, speaker = speaker)
    }

    private fun token(text: String, isFinal: Boolean, speaker: String? = null): JSONObject {
        val obj = JSONObject()
            .put("text", text)
            .put("is_final", isFinal)
        if (speaker != null) obj.put("speaker", speaker)
        return obj
    }
}
