package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.GeminiTranscriptionClient.Companion.SetupTier
import helium314.keyboard.latin.voice.GeminiTranscriptionClient.Companion.TranscriptAccumulator
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wire-format and transcript-assembly tests for the Gemini Live transcription
 * client. These cover the parts of the protocol that are easy to get wrong and
 * that fail silently or catastrophically at runtime: `setup` field placement,
 * base64 audio framing, and reconstruction of editor segments from the server's
 * finalized transcripts.
 *
 * Robolectric is required because the client builds its JSON payloads with
 * `org.json`, which is an unimplemented stub in the plain JVM test classpath.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiTranscriptionClientTest {

    // ── setup message ──────────────────────────────────────────────────

    @Test
    fun setupMessage_placesTranscriptionConfigBesideGenerationConfig() {
        val setup = setupObject(SetupTier.FULL)

        // Nesting inputAudioTranscription inside generationConfig is the
        // documented failure that closes the socket with code 1007.
        assertTrue(setup.has("inputAudioTranscription"))
        assertFalse(setup.getJSONObject("generationConfig").has("inputAudioTranscription"))
    }

    @Test
    fun setupMessage_requestsTextOnlyOutputFromTheTranscribeModel() {
        val setup = setupObject(SetupTier.FULL)

        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        val modalities = setup.getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("TEXT", modalities.getString(0))
    }

    @Test
    fun setupMessage_sendsAccuracyTunedTranscriptionConfig() {
        val setup = setupObject(
            SetupTier.FULL,
            config = sessionConfig(
                languageTag = "en_US",
                userVocabulary = listOf("Roentgen"),
                endOfSpeechSilenceMs = 1800
            )
        )
        val transcription = setup.getJSONObject("inputAudioTranscription")

        assertEquals("SMART", transcription.getString("mode"))
        assertEquals("en-US", transcription.getJSONArray("languageCodes").getString(0))
        assertEquals("Roentgen", transcription.getJSONArray("customVocabulary").getString(0))

        val vad = setup.getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")
        // Server VAD stays on so speech onset keeps its prefix padding; only the
        // end of speech is made patient.
        assertFalse(vad.getBoolean("disabled"))
        assertEquals("START_SENSITIVITY_HIGH", vad.getString("startOfSpeechSensitivity"))
        assertEquals("END_SENSITIVITY_LOW", vad.getString("endOfSpeechSensitivity"))
        assertEquals(1800, vad.getInt("silenceDurationMs"))
        assertEquals(300, vad.getInt("prefixPaddingMs"))
    }

    @Test
    fun setupMessage_sendsEmptyLanguageCodesWhenAutoDetectRequested() {
        val setup = setupObject(
            SetupTier.FULL,
            config = sessionConfig(languageTag = "en_US", autoDetectLanguage = true)
        )
        assertEquals(0, setup.getJSONObject("inputAudioTranscription").getJSONArray("languageCodes").length())
    }

    @Test
    fun setupTiers_dropOneUnsupportedFeatureAtATime() {
        val config = sessionConfig(userVocabulary = listOf("Roentgen"))

        val full = setupObject(SetupTier.FULL, config)
        assertTrue(full.has("systemInstruction"))
        assertTrue(full.has("realtimeInputConfig"))

        val noSystemInstruction = setupObject(SetupTier.NO_SYSTEM_INSTRUCTION, config)
        assertFalse(noSystemInstruction.has("systemInstruction"))
        assertTrue(noSystemInstruction.has("realtimeInputConfig"))
        assertTrue(noSystemInstruction.getJSONObject("inputAudioTranscription").has("customVocabulary"))

        val noRealtimeConfig = setupObject(SetupTier.NO_REALTIME_CONFIG, config)
        assertFalse(noRealtimeConfig.has("realtimeInputConfig"))
        assertTrue(noRealtimeConfig.getJSONObject("inputAudioTranscription").has("mode"))

        // The documented minimum from the Live Transcription quickstart.
        val minimal = setupObject(SetupTier.MINIMAL, config)
        val minimalTranscription = minimal.getJSONObject("inputAudioTranscription")
        assertFalse(minimalTranscription.has("mode"))
        assertFalse(minimalTranscription.has("customVocabulary"))
        assertTrue(minimalTranscription.has("languageCodes"))
        assertTrue(minimal.has("generationConfig"))
    }

    @Test
    fun setupTiers_terminateAtTheMinimalPayload() {
        assertEquals(SetupTier.NO_SYSTEM_INSTRUCTION, SetupTier.FULL.next())
        assertEquals(SetupTier.NO_REALTIME_CONFIG, SetupTier.NO_SYSTEM_INSTRUCTION.next())
        assertEquals(SetupTier.MINIMAL, SetupTier.NO_REALTIME_CONFIG.next())
        assertNull(SetupTier.MINIMAL.next())
    }

    // ── audio framing ──────────────────────────────────────────────────

    @Test
    fun audioMessage_sendsUnwrappedBase64PcmWithTheSampleRateInTheMimeType() {
        // Long enough that a line-wrapping base64 encoder would insert a newline.
        val pcm = ByteArray(600) { (it % 251).toByte() }
        val audio = JSONObject(GeminiTranscriptionClient.buildAudioMessage(pcm))
            .getJSONObject("realtimeInput")
            .getJSONObject("audio")

        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        val encoded = audio.getString("data")
        assertFalse(encoded.contains('\n'))
        assertContentEquals(pcm, encoded.decodeBase64()!!.toByteArray())
    }

    @Test
    fun audioStreamEndMessage_matchesTheHybridVadPayload() {
        val realtimeInput = JSONObject(GeminiTranscriptionClient.AUDIO_STREAM_END_MESSAGE)
            .getJSONObject("realtimeInput")
        assertTrue(realtimeInput.getBoolean("audioStreamEnd"))
        assertEquals(1, realtimeInput.length())
    }

    // ── language resolution ────────────────────────────────────────────

    @Test
    fun resolveLanguageCode_prefersTheExactSupportedRegionalVariant() {
        assertEquals("en-GB", GeminiTranscriptionClient.resolveLanguageCode("en_GB"))
        assertEquals("en-IN", GeminiTranscriptionClient.resolveLanguageCode("en-IN"))
        assertEquals("pt-PT", GeminiTranscriptionClient.resolveLanguageCode("pt_PT"))
    }

    @Test
    fun resolveLanguageCode_fallsBackToTheLanguageDefaultForUnlistedRegions() {
        assertEquals("en-US", GeminiTranscriptionClient.resolveLanguageCode("en"))
        assertEquals("de-DE", GeminiTranscriptionClient.resolveLanguageCode("de_AT"))
        // Gemini lists no es-ES, so European Spanish maps to the regional default.
        assertEquals("es-419", GeminiTranscriptionClient.resolveLanguageCode("es_ES"))
        assertEquals("cmn-Hans-CN", GeminiTranscriptionClient.resolveLanguageCode("zh_Hans_CN"))
        assertEquals("he-IL", GeminiTranscriptionClient.resolveLanguageCode("iw_IL"))
        assertEquals("nb-NO", GeminiTranscriptionClient.resolveLanguageCode("no"))
    }

    @Test
    fun resolveLanguageCode_returnsNullSoUnknownSubtypesAutoDetect() {
        assertNull(GeminiTranscriptionClient.resolveLanguageCode(null))
        assertNull(GeminiTranscriptionClient.resolveLanguageCode(""))
        assertNull(GeminiTranscriptionClient.resolveLanguageCode("und"))
        assertNull(GeminiTranscriptionClient.resolveLanguageCode("zz"))
        assertNull(GeminiTranscriptionClient.resolveLanguageCode("tlh"))
    }

    @Test
    fun everyResolvedLanguageCodeIsOneGeminiDocuments() {
        val supported = GeminiTranscriptionClient.SUPPORTED_LANGUAGE_CODES.toSet()
        val subtypeTags = listOf(
            "en", "en_US", "en_GB", "en_IN", "de", "de_DE", "fr", "fr_CA", "es", "es_MX",
            "pt", "pt_BR", "it", "nl", "pl", "ru", "uk", "tr", "ar", "he", "iw", "hi",
            "bn", "ta", "zh", "zh_CN", "yue", "ja", "ko", "th", "vi", "id", "ms", "fil",
            "tl", "sv", "da", "nb", "no", "fi", "cs", "sk", "hu", "ro", "bg", "hr", "sr",
            "sl", "et", "lv", "lt", "el", "fa", "ur", "sw", "af", "ca", "gl", "eu"
        )
        for (tag in subtypeTags) {
            val resolved = GeminiTranscriptionClient.resolveLanguageCode(tag) ?: continue
            assertContains(supported, resolved, "resolved code for '$tag' is not a documented one")
        }
    }

    // ── server message parsing ─────────────────────────────────────────

    @Test
    fun parsesFinalizedTranscriptButNotTheInterimHypothesis() {
        val interim = JSONObject(
            """{"serverContent":{"interimInputTranscription":{"text":"hello wor"}}}"""
        )
        assertEquals("hello wor", GeminiTranscriptionClient.extractInterimTranscript(interim))
        assertNull(GeminiTranscriptionClient.extractFinalTranscript(interim))

        val final = JSONObject(
            """{"serverContent":{"inputTranscription":{"text":"Hello world.","finished":true}}}"""
        )
        assertEquals("Hello world.", GeminiTranscriptionClient.extractFinalTranscript(final))
        assertNull(GeminiTranscriptionClient.extractInterimTranscript(final))
    }

    @Test
    fun ignoresGeneratedModelTextSoNoResponseLeaksIntoTheEditor() {
        val json = JSONObject(
            """{"serverContent":{"modelTurn":{"parts":[{"text":"Sure, here is a story."}]}}}"""
        )
        assertNull(GeminiTranscriptionClient.extractFinalTranscript(json))
        assertNull(GeminiTranscriptionClient.extractInterimTranscript(json))
    }

    @Test
    fun recognizesSetupCompleteAndTurnComplete() {
        assertTrue(GeminiTranscriptionClient.isSetupComplete(JSONObject("""{"setupComplete":{}}""")))
        assertFalse(GeminiTranscriptionClient.isSetupComplete(JSONObject("""{"serverContent":{}}""")))
        assertTrue(
            GeminiTranscriptionClient.isTurnComplete(
                JSONObject("""{"serverContent":{"turnComplete":true}}""")
            )
        )
        assertFalse(
            GeminiTranscriptionClient.isTurnComplete(
                JSONObject("""{"serverContent":{"generationComplete":true}}""")
            )
        )
    }

    @Test
    fun parsesGoAwayDurationFromItsProtobufStringForm() {
        assertEquals(
            30_000L,
            GeminiTranscriptionClient.extractGoAwayMillis(JSONObject("""{"goAway":{"timeLeft":"30s"}}"""))
        )
        assertEquals(
            10_500L,
            GeminiTranscriptionClient.extractGoAwayMillis(JSONObject("""{"goAway":{"timeLeft":"10.5s"}}"""))
        )
        // An unparseable or absent duration must still be treated as "leaving now".
        assertEquals(
            0L,
            GeminiTranscriptionClient.extractGoAwayMillis(JSONObject("""{"goAway":{}}"""))
        )
        assertNull(
            GeminiTranscriptionClient.extractGoAwayMillis(JSONObject("""{"serverContent":{}}"""))
        )
    }

    @Test
    fun mapsApiStatusesToActionableMessages() {
        assertEquals(
            "Invalid Gemini API key. Please check Settings.",
            GeminiTranscriptionClient.extractErrorMessage(
                JSONObject("""{"error":{"status":"UNAUTHENTICATED","message":"API key not valid"}}""")
            )
        )
        assertEquals(
            "Gemini rate limited — too many requests",
            GeminiTranscriptionClient.extractErrorMessage(
                JSONObject("""{"error":{"status":"RESOURCE_EXHAUSTED"}}""")
            )
        )
        // Unknown statuses keep the server's own wording rather than hiding it.
        assertEquals(
            "WEIRD_STATUS: something odd",
            GeminiTranscriptionClient.extractErrorMessage(
                JSONObject("""{"error":{"status":"WEIRD_STATUS","message":"something odd"}}""")
            )
        )
        assertNull(GeminiTranscriptionClient.extractErrorMessage(JSONObject("""{"serverContent":{}}""")))
    }

    @Test
    fun closeFailureDescriptionSurfacesTheServerReason() {
        assertEquals(
            "Invalid Gemini API key. Please check Settings.",
            GeminiTranscriptionClient.describeCloseFailure(1007, "Request contains an invalid API key not valid")
        )
        assertEquals(
            "Gemini rejected the transcription session setup",
            GeminiTranscriptionClient.describeCloseFailure(
                1007,
                "Invalid JSON payload received. Unknown name \"inputAudioTranscription\""
            )
        )
        assertEquals(
            "Gemini stream ended unexpectedly",
            GeminiTranscriptionClient.describeCloseFailure(1011, "")
        )
    }

    // ── transcript assembly ────────────────────────────────────────────

    @Test
    fun accumulator_appendsIndependentUtterancesAsSeparateSegments() {
        val accumulator = TranscriptAccumulator()

        val first = assertNotNull(accumulator.accept("Hello world."))
        assertEquals("Hello world.", first.text)
        assertFalse(first.attachesToPrevious)

        val second = assertNotNull(accumulator.accept("How are you?"))
        assertEquals("How are you?", second.text)
        assertFalse(second.attachesToPrevious)
    }

    @Test
    fun accumulator_emitsOnlyTheSuffixWhenTheServerResendsGrowingText() {
        val accumulator = TranscriptAccumulator()

        assertEquals("Hello", accumulator.accept("Hello")?.text)
        val extension = assertNotNull(accumulator.accept("Hello world"))
        assertEquals("world", extension.text)
        assertFalse(extension.attachesToPrevious)
    }

    @Test
    fun accumulator_attachesASuffixThatResumesMidWord() {
        val accumulator = TranscriptAccumulator()

        assertEquals("head", accumulator.accept("head")?.text)
        val continuation = assertNotNull(accumulator.accept("heading"))
        assertEquals("ing", continuation.text)
        assertTrue(continuation.attachesToPrevious)
    }

    @Test
    fun accumulator_attachesLeadingPunctuationToThePreviousWord() {
        val accumulator = TranscriptAccumulator()

        accumulator.accept("Hello")
        val punctuation = assertNotNull(accumulator.accept(", world"))
        assertEquals(", world", punctuation.text)
        assertTrue(punctuation.attachesToPrevious)
    }

    @Test
    fun accumulator_dropsAnIdenticalRepeatSoReconnectsDoNotDuplicateText() {
        val accumulator = TranscriptAccumulator()

        assertEquals("Hello world.", accumulator.accept("Hello world.")?.text)
        assertNull(accumulator.accept("Hello world."))
    }

    @Test
    fun accumulator_dropsWhitespaceOnlyAndEmptyTranscripts() {
        val accumulator = TranscriptAccumulator()

        assertNull(accumulator.accept(""))
        accumulator.accept("Hello")
        assertNull(accumulator.accept("Hello   "))
    }

    @Test
    fun accumulator_startsFreshAfterATurnBoundary() {
        val accumulator = TranscriptAccumulator()

        assertEquals("Hello world.", accumulator.accept("Hello world.")?.text)
        accumulator.reset()
        // The same text in a new turn is a real repeat the user spoke again.
        assertEquals("Hello world.", accumulator.accept("Hello world.")?.text)
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun sessionConfig(
        languageTag: String? = "en_US",
        autoDetectLanguage: Boolean = false,
        transcriptionMode: String = "SMART",
        endOfSpeechSilenceMs: Int = 1500,
        userVocabulary: List<String> = emptyList(),
        editorContext: String? = null
    ) = GeminiTranscriptionClient.buildSessionConfig(
        languageTag = languageTag,
        autoDetectLanguage = autoDetectLanguage,
        transcriptionMode = transcriptionMode,
        endOfSpeechSilenceMs = endOfSpeechSilenceMs,
        userVocabulary = userVocabulary,
        editorContext = editorContext,
        builtInVocabulary = emptyList()
    )

    private fun setupObject(
        tier: SetupTier,
        config: GeminiTranscriptionClient.Companion.SessionConfig = sessionConfig()
    ): JSONObject =
        JSONObject(GeminiTranscriptionClient.buildSetupMessage(config, tier)).getJSONObject("setup")

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
