package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssemblyAITranscriptionClientTest {

    @Test
    fun buildConnectionUrl_includesRequiredParametersForUniversalStreaming() {
        val cfg = AssemblyAITranscriptionClient.buildSessionConfig(
            speechModel = "universal-streaming-english",
            sampleRate = 16000,
            formatTurns = true,
            endOfTurnConfidenceThreshold = 0.7,
            minTurnSilenceMs = 600,
            maxTurnSilenceMs = 2400,
            keyterms = listOf("HeliBoard", "AssemblyAI"),
            useEuEndpoint = false,
        )
        val url = AssemblyAITranscriptionClient.buildConnectionUrl(cfg)

        assertEquals("streaming.assemblyai.com", url.host)
        assertEquals("/v3/ws", url.encodedPath)
        assertEquals("universal-streaming-english", url.queryParameter("speech_model"))
        assertEquals("16000", url.queryParameter("sample_rate"))
        assertEquals("true", url.queryParameter("format_turns"))
        assertEquals("0.7", url.queryParameter("end_of_turn_confidence_threshold"))
        assertEquals("600", url.queryParameter("min_turn_silence"))
        assertEquals("2400", url.queryParameter("max_turn_silence"))

        val keytermsJson = url.queryParameter("keyterms_prompt")
        assertNotNull(keytermsJson)
        val arr = JSONArray(keytermsJson)
        assertEquals(2, arr.length())
        assertEquals("HeliBoard", arr.getString(0))
    }

    @Test
    fun buildConnectionUrl_switchesToEuEndpointWhenRequested() {
        val cfg = AssemblyAITranscriptionClient.buildSessionConfig(
            speechModel = "u3-rt-pro",
            useEuEndpoint = true,
        )
        val url = AssemblyAITranscriptionClient.buildConnectionUrl(cfg)
        assertEquals("streaming.eu.assemblyai.com", url.host)
        assertEquals("u3-rt-pro", url.queryParameter("speech_model"))
    }

    @Test
    fun buildSessionConfig_clampsConfidenceAndSilenceRanges() {
        val cfg = AssemblyAITranscriptionClient.buildSessionConfig(
            endOfTurnConfidenceThreshold = 1.5,
            minTurnSilenceMs = -100,
            maxTurnSilenceMs = 50,
        )

        assertEquals(1.0, cfg.endOfTurnConfidenceThreshold)
        assertTrue(cfg.minTurnSilenceMs >= 0)
        // maxTurnSilenceMs is forced up to at least minTurnSilenceMs and the
        // documented floor of 80ms for AssemblyAI.
        assertTrue(cfg.maxTurnSilenceMs >= cfg.minTurnSilenceMs)
        assertTrue(cfg.maxTurnSilenceMs >= 80)
    }

    @Test
    fun buildSessionConfig_filtersOversizedKeyterms() {
        val tooLong = "x".repeat(60)
        val cfg = AssemblyAITranscriptionClient.buildSessionConfig(
            keyterms = listOf("HeliBoard", "", tooLong, "HeliBoard")
        )
        assertEquals(listOf("HeliBoard"), cfg.keyterms)
    }

    @Test
    fun buildSessionConfig_capsKeytermsAtHundred() {
        val keyterms = (1..150).map { "term-$it" }
        val cfg = AssemblyAITranscriptionClient.buildSessionConfig(keyterms = keyterms)
        assertEquals(100, cfg.keyterms.size)
    }

    @Test
    fun parseServerEvent_parsesBeginEvent() {
        val event = AssemblyAITranscriptionClient.parseServerEvent(
            """{"type":"Begin","id":"abc-123","expires_at":1234567890}"""
        )
        val begin = assertIs<AssemblyAIServerEvent.Begin>(event)
        assertEquals("abc-123", begin.sessionId)
    }

    @Test
    fun parseServerEvent_parsesEndOfTurnFormatted() {
        val event = AssemblyAITranscriptionClient.parseServerEvent(
            """{"type":"Turn","turn_order":1,"end_of_turn":true,"turn_is_formatted":true,"transcript":"Hello, world.","end_of_turn_confidence":0.92}"""
        )
        val turn = assertIs<AssemblyAIServerEvent.Turn>(event)
        assertTrue(turn.endOfTurn)
        assertTrue(turn.isFormatted)
        assertEquals("Hello, world.", turn.transcript)
        assertEquals(1, turn.turnOrder)
    }

    @Test
    fun parseServerEvent_parsesPartialTurnAsNonEndOfTurn() {
        val event = AssemblyAITranscriptionClient.parseServerEvent(
            """{"type":"Turn","turn_order":3,"end_of_turn":false,"turn_is_formatted":false,"transcript":"hello there","end_of_turn_confidence":0.3}"""
        )
        val turn = assertIs<AssemblyAIServerEvent.Turn>(event)
        assertFalse(turn.endOfTurn)
        assertFalse(turn.isFormatted)
        assertEquals("hello there", turn.transcript)
    }

    @Test
    fun parseServerEvent_parsesTermination() {
        val event = AssemblyAITranscriptionClient.parseServerEvent(
            """{"type":"Termination","audio_duration_seconds":12.5,"session_duration_seconds":15.0}"""
        )
        val term = assertIs<AssemblyAIServerEvent.Termination>(event)
        assertEquals(12.5, term.audioDurationSeconds)
    }

    @Test
    fun parseServerEvent_parsesError() {
        val event = AssemblyAITranscriptionClient.parseServerEvent(
            """{"type":"Error","error":"invalid api key"}"""
        )
        val err = assertIs<AssemblyAIServerEvent.Error>(event)
        assertTrue(err.description.contains("invalid"))
    }

    @Test
    fun parseServerEvent_returnsNullForUnknownType() {
        val event = AssemblyAITranscriptionClient.parseServerEvent(
            """{"type":"Unknown"}"""
        )
        assertNull(event)
    }

    @Test
    fun defaultKeyterms_includesProductBranding() {
        val keyterms = AssemblyAITranscriptionClient.defaultKeyterms()
        assertTrue(keyterms.contains("HeliBoard"))
        assertTrue(keyterms.contains("AssemblyAI"))
        assertTrue(keyterms.size <= 100)
        assertTrue(keyterms.all { it.length <= 50 })
    }
}
