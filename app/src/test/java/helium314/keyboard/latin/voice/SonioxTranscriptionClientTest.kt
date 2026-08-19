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
        assertEquals("stt-rt-v5", payload.getString("model"))
        assertEquals(SonioxTranscriptionClient.MODEL, payload.getString("model"))
        assertEquals("pcm_s16le", payload.getString("audio_format"))
        assertEquals(VoiceRecorder.SAMPLE_RATE, payload.getInt("sample_rate"))
        assertEquals(1, payload.getInt("num_channels"))
        assertTrue(payload.getBoolean("enable_endpoint_detection"))
        assertEquals(2000, payload.getInt("max_endpoint_delay_ms"))
        assertEquals(
            SonioxTranscriptionClient.DEFAULT_ENDPOINT_SENSITIVITY,
            payload.getDouble("endpoint_sensitivity"),
            1e-9
        )
        assertFalse(payload.has("endpoint_latency_adjustment_level"))
        assertFalse(payload.getBoolean("enable_speaker_diarization"))
        assertTrue(payload.getBoolean("language_hints_strict"))

        val hints = payload.getJSONArray("language_hints")
        assertEquals(1, hints.length())
        assertEquals("en", hints.getString(0))

        val terms = payload.getJSONObject("context").getJSONArray("terms")
        assertTrue((0 until terms.length()).any { terms.getString(it) == "HeliBoard" })
        assertTrue((0 until terms.length()).any { terms.getString(it) == "Soniox" })
    }

    @Test
    fun buildStartConfigMessage_alwaysIncludesGeneralContext() {
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

        val context = payload.getJSONObject("context")
        assertFalse(context.has("terms"))
        val general = generalContextMap(context)
        assertEquals("Mobile keyboard dictation", general["domain"])
        assertEquals("User dictating text into a mobile app", general["setting"])
        assertEquals("Dictation", general["topic"])
        assertEquals("HeliBoard", general["product"])
        assertEquals("English", general["language"])
        assertEquals(
            "User is dictating in English. Output transcription only in English.",
            general["instructions"]
        )
        assertFalse(general.containsKey("speakers"))
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
    fun buildSessionConfig_clampsEndpointSensitivityWithinAllowedRange() {
        val tooSmall = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false,
            endpointSensitivity = -5.0
        )
        val tooLarge = SonioxTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            enableEndpointDetection = true,
            maxEndpointDelayMs = 2000,
            diarizationEnabled = false,
            endpointSensitivity = 4.0
        )
        assertEquals(
            SonioxTranscriptionClient.MIN_ENDPOINT_SENSITIVITY,
            tooSmall.endpointSensitivity,
            1e-9
        )
        assertEquals(
            SonioxTranscriptionClient.MAX_ENDPOINT_SENSITIVITY,
            tooLarge.endpointSensitivity,
            1e-9
        )
    }

    @Test
    fun languageDisplayName_usesEnglishName() {
        assertEquals("English", SonioxTranscriptionClient.languageDisplayName("en"))
        assertEquals("Spanish", SonioxTranscriptionClient.languageDisplayName("es"))
        assertNull(SonioxTranscriptionClient.languageDisplayName(null))
        assertNull(SonioxTranscriptionClient.languageDisplayName("  "))
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
        assertFalse(payload.has("max_endpoint_delay_ms"))
        assertFalse(payload.has("endpoint_sensitivity"))
        assertEquals("fr", payload.getJSONArray("language_hints").getString(0))
        assertTrue(payload.getBoolean("language_hints_strict"))
        val general = generalContextMap(payload.getJSONObject("context"))
        assertEquals("French", general["language"])
        assertEquals("1 speaker (local user dictating)", general["speakers"])
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
            assertFalse(payload.has("language_hints_strict"), "tag=$tag should omit language_hints_strict")
            val general = generalContextMap(payload.getJSONObject("context"))
            assertFalse(general.containsKey("language"), "tag=$tag should omit general language")
            assertFalse(general.containsKey("instructions"), "tag=$tag should omit language instructions")
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
        // Last char "." is not wordy, so a follow-up chunk should NOT be
        // treated as a word continuation.
        assertFalse(result.tailIsWordy)
    }

    @Test
    fun buildSegmentFromFinalTokens_marksMidWordContinuationWhenSecondChunkHasNoLeadingSpace() {
        // Soniox finalizes the first half of "heading" prematurely. The next
        // response carries the rest of the word with NO leading space token,
        // signaling that it continues the previous word rather than starting
        // a new one. Without this signal the IME would emit "head ing".
        val first = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = JSONArray().put(finalToken("I'm head")),
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = false
        )
        assertEquals("I'm head", assertNotNull(first.segment).text)
        assertFalse(first.segment!!.attachesToPrevious)
        assertTrue(first.tailIsWordy, "trailing 'd' should be wordy")

        val second = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = JSONArray()
                .put(finalToken("ing"))
                .put(finalToken(" over"))
                .put(finalToken(" to"))
                .put(finalToken(" the"))
                .put(finalToken(" gym")),
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = first.tailIsWordy
        )
        val secondSegment = assertNotNull(second.segment)
        assertEquals("ing over to the gym", secondSegment.text)
        assertTrue(
            secondSegment.attachesToPrevious,
            "continuation of split word should attach so IME does not insert a space"
        )
        assertTrue(second.tailIsWordy)
    }

    @Test
    fun buildSegmentFromFinalTokens_doesNotAttachWhenNextChunkHasLeadingSpaceToken() {
        // After a real word boundary, Soniox emits a leading space token.
        // Even if the previous chunk ended on a wordy character, a leading
        // space means a fresh word and the IME should add the usual
        // separator.
        val tokens = JSONArray()
            .put(finalToken(" "))
            .put(finalToken("over"))
            .put(finalToken(" the"))
            .put(finalToken(" gym"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = true
        )
        val segment = assertNotNull(result.segment)
        assertEquals("over the gym", segment.text)
        assertFalse(segment.attachesToPrevious)
    }

    @Test
    fun buildSegmentFromFinalTokens_doesNotAttachAfterPunctuationTail() {
        // Previous chunk ended with "." (not wordy) — this is a real
        // sentence boundary, so the next chunk must start a new word
        // regardless of whether it has a leading space.
        val tokens = JSONArray().put(finalToken("Hello"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = false
        )
        val segment = assertNotNull(result.segment)
        assertFalse(segment.attachesToPrevious)
    }

    @Test
    fun buildSegmentFromFinalTokens_doesNotAttachOnSessionStart() {
        // Default state at session start: previousTailIsWordy = false.
        val tokens = JSONArray().put(finalToken("Hello"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false
        )
        val segment = assertNotNull(result.segment)
        assertFalse(segment.attachesToPrevious)
    }

    @Test
    fun buildSegmentFromFinalTokens_endpointMarkerDoesNotResetTailWordiness() {
        // Endpoint detection triggers `<end>` after a finalized token. The
        // marker is filtered from the assembled text, but it must NOT erase
        // the wordiness of the real trailing token — otherwise Soniox-split
        // words across endpoint events would slip through.
        val tokens = JSONArray()
            .put(finalToken("head"))
            .put(finalToken("<end>"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = false
        )
        assertEquals("head", assertNotNull(result.segment).text)
        assertTrue(result.tailIsWordy, "trailing 'd' should remain wordy after <end>")
    }

    @Test
    fun buildSegmentFromFinalTokens_apostropheTailKeepsContinuationSignal() {
        // English contractions can land an apostrophe at the chunk boundary
        // (e.g. "don'" + "t"). Treat `'` as wordy so the next chunk attaches.
        val first = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = JSONArray().put(finalToken("don'")),
            primarySpeaker = null,
            diarizationEnabled = false
        )
        assertTrue(first.tailIsWordy)

        val second = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = JSONArray().put(finalToken("t")),
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = first.tailIsWordy
        )
        assertTrue(assertNotNull(second.segment).attachesToPrevious)
    }


    @Test
    fun buildSegmentFromFinalTokens_attachesWhenSegmentStartsWithSubwordContinuation() {
        // The previous response finalized text ending on a wordy character
        // (e.g. "head" → tail = 'd'), and Soniox continues with "ing"
        // without a leading space token. That combination signals a mid-word
        // continuation, so the chunk must attach.
        val tokens = JSONArray()
            .put(finalToken("ing"))

        val result = SonioxTranscriptionClient.buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = null,
            diarizationEnabled = false,
            previousTailIsWordy = true
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
    fun finalizeControlMessage_isValidFinalizeJson() {
        val json = JSONObject(SonioxTranscriptionClient.FINALIZE_CONTROL_MESSAGE)
        assertEquals("finalize", json.getString("type"))
        // Soniox only accepts "finalize" or "keepalive" control types; make
        // sure we never send extra keys it might reject.
        assertEquals(1, json.length())
    }

    @Test
    fun keepaliveControlMessage_isValidKeepaliveJson() {
        val json = JSONObject(SonioxTranscriptionClient.KEEPALIVE_CONTROL_MESSAGE)
        assertEquals("keepalive", json.getString("type"))
        assertEquals(1, json.length())
    }

    @Test
    fun buildErrorDescription_combinesCodeAndMessage() {
        val payload = JSONObject()
            .put("error_code", 401)
            .put("error_type", "unauthenticated")
            .put("error_message", "Incorrect API key")
            .put("request_id", "req_123")
        val description = SonioxTranscriptionClient.buildErrorDescription(payload)
        assertEquals(
            "Invalid Soniox API key. Please check Settings. (request_id=req_123)",
            description
        )
    }

    @Test
    fun buildErrorDescription_appendsMoreInfoAndMapsCreditErrors() {
        val payload = JSONObject()
            .put("error_code", 402)
            .put("error_type", "organization_balance_exhausted")
            .put("error_message", "Organization balance exhausted.")
            .put("more_info", "https://soniox.com/docs/api-reference/errors")
            .put("request_id", "req_402")
        val description = SonioxTranscriptionClient.buildErrorDescription(payload)
        assertEquals(
            "Soniox account is out of credits. Add funds in the Soniox console. " +
                "(request_id=req_402) https://soniox.com/docs/api-reference/errors",
            description
        )
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

    @Test
    fun tokensContainFinalTokens_detectsFinalAndNonFinalTokens() {
        assertFalse(SonioxTranscriptionClient.tokensContainFinalTokens(null))
        assertFalse(SonioxTranscriptionClient.tokensContainFinalTokens(JSONArray()))
        assertFalse(
            SonioxTranscriptionClient.tokensContainFinalTokens(
                JSONArray().put(token(text = "hello", isFinal = false))
            )
        )
        assertTrue(
            SonioxTranscriptionClient.tokensContainFinalTokens(
                JSONArray().put(finalToken("<fin>"))
            )
        )
        assertTrue(
            SonioxTranscriptionClient.tokensContainFinalTokens(
                JSONArray().put(finalToken("hello"))
            )
        )
    }

    private fun generalContextMap(context: JSONObject): Map<String, String> {
        val general = context.optJSONArray("general") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (i in 0 until general.length()) {
            val item = general.getJSONObject(i)
            map[item.getString("key")] = item.getString("value")
        }
        return map
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
