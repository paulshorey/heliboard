package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeechmaticsTranscriptionClientTest {

    @Test
    fun buildStartRecognitionMessage_usesSpeechmaticsRealtimeConfig() {
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(
                language = "en",
                sessionConfig = SpeechmaticsSessionConfig(
                    maxDelaySeconds = 1.2,
                    endOfUtteranceSilenceSeconds = 0.9,
                    removeDisfluencies = true,
                    outputLocale = "en-US"
                )
            )
        )

        assertEquals("StartRecognition", payload.getString("message"))

        val audioFormat = payload.getJSONObject("audio_format")
        assertEquals("raw", audioFormat.getString("type"))
        assertEquals("pcm_s16le", audioFormat.getString("encoding"))
        assertEquals(VoiceRecorder.SAMPLE_RATE, audioFormat.getInt("sample_rate"))

        val config = payload.getJSONObject("transcription_config")
        assertEquals("en", config.getString("language"))
        assertEquals("en-US", config.getString("output_locale"))
        assertEquals(1.2, config.getDouble("max_delay"))
        assertEquals("flexible", config.getString("max_delay_mode"))
        assertFalse(config.getBoolean("enable_partials"))
        assertTrue(config.getBoolean("enable_entities"))
        assertEquals(
            0.9,
            config.getJSONObject("conversation_config")
                .getDouble("end_of_utterance_silence_trigger")
        )
        assertTrue(
            config.getJSONObject("transcript_filtering_config")
                .getBoolean("remove_disfluencies")
        )
    }

    @Test
    fun buildStartRecognitionMessage_fallsBackToEnglishWhenLanguageMissing() {
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(
                language = null,
                sessionConfig = SpeechmaticsSessionConfig()
            )
        )
        val config = payload.getJSONObject("transcription_config")

        assertEquals("en", config.getString("language"))
    }

    @Test
    fun buildStartRecognitionMessage_skipsOutputLocaleWhenMismatched() {
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(
                language = "fr",
                sessionConfig = SpeechmaticsSessionConfig(outputLocale = "en-GB")
            )
        )
        val config = payload.getJSONObject("transcription_config")

        assertFalse(config.has("output_locale"))
    }

    @Test
    fun parseServerEvent_parsesRecognitionStarted() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """{"message":"RecognitionStarted"}"""
        )

        assertEquals(SpeechmaticsServerEvent.RecognitionStarted, event)
    }

    @Test
    fun parseServerEvent_parsesAudioAcknowledgement() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """{"message":"AudioAdded","seq_no":12}"""
        )

        val audioAdded = assertIs<SpeechmaticsServerEvent.AudioAdded>(event)
        assertEquals(12, audioAdded.sequenceNumber)
    }

    @Test
    fun parseServerEvent_parsesFinalTranscriptMetadata() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":1.25,
                "end_time":2.75,
                "transcript":"hello from speechmatics"
              }
            }
            """.trimIndent()
        )

        val transcript = assertIs<SpeechmaticsServerEvent.FinalTranscript>(event)
        assertEquals("hello from speechmatics", transcript.transcript)
        assertEquals(1.25, transcript.startTime)
        assertEquals(2.75, transcript.endTime)
    }

    @Test
    fun parseServerEvent_ignoresBlankTranscriptPayloads() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":0.0,
                "end_time":0.3,
                "transcript":"   "
              }
            }
            """.trimIndent()
        )

        assertEquals(null, event)
    }

    @Test
    fun parseServerEvent_formatsErrorMessage() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"Error",
              "type":"quota_exceeded",
              "code":"4005",
              "reason":"quota exceeded",
              "detail":"upgrade required"
            }
            """.trimIndent()
        )

        val error = assertIs<SpeechmaticsServerEvent.Error>(event)
        assertTrue(error.description.contains("quota_exceeded"))
        assertTrue(error.description.contains("4005"))
        assertTrue(error.description.contains("upgrade required"))
    }

    @Test
    fun parseServerEvent_parsesEndOfTranscript() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """{"message":"EndOfTranscript"}"""
        )

        assertEquals(SpeechmaticsServerEvent.EndOfTranscript, event)
    }

    @Test
    fun parseServerEvent_parsesEndOfUtterance() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """{"message":"EndOfUtterance"}"""
        )

        assertEquals(SpeechmaticsServerEvent.EndOfUtterance, event)
    }
}
