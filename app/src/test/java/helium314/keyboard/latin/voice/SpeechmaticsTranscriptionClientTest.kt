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
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en-US",
            maxDelaySeconds = 2.0,
            removeDisfluencies = true,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.25
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )

        assertEquals("StartRecognition", payload.getString("message"))

        val audioFormat = payload.getJSONObject("audio_format")
        assertEquals("raw", audioFormat.getString("type"))
        assertEquals("pcm_s16le", audioFormat.getString("encoding"))
        assertEquals(VoiceRecorder.SAMPLE_RATE, audioFormat.getInt("sample_rate"))

        val config = payload.getJSONObject("transcription_config")
        assertEquals("en", config.getString("language"))
        assertEquals("en-US", config.getString("output_locale"))
        assertEquals(2.0, config.getDouble("max_delay"))
        assertEquals("flexible", config.getString("max_delay_mode"))
        assertFalse(config.getBoolean("enable_partials"))
        assertTrue(config.getBoolean("enable_entities"))
        assertFalse(config.has("conversation_config"))
        assertTrue(
            config.getJSONObject("transcript_filtering_config")
                .getBoolean("remove_disfluencies")
        )
        assertEquals(
            0.25,
            config.getJSONObject("punctuation_overrides").getDouble("sensitivity")
        )
    }

    @Test
    fun buildStartRecognitionMessage_fallsBackToEnglishWhenLanguageMissing() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = null,
            maxDelaySeconds = 2.0,
            removeDisfluencies = true,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.25
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")

        assertEquals("en", config.getString("language"))
    }

    @Test
    fun buildStartRecognitionMessage_skipsOutputLocaleWhenMismatched() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "fr",
            maxDelaySeconds = 2.0,
            removeDisfluencies = true,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.25
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
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
              },
              "results":[
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"hello"}]
                },
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"from"}]
                },
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"speechmatics"}]
                }
              ]
            }
            """.trimIndent()
        )

        val transcript = assertIs<SpeechmaticsServerEvent.FinalTranscript>(event)
        assertEquals("hello from speechmatics", transcript.transcript)
        assertFalse(transcript.attachesToPrevious)
        assertEquals(1.25, transcript.startTime)
        assertEquals(2.75, transcript.endTime)
    }

    @Test
    fun parseServerEvent_reconstructsSpacingFromTokenAttachments() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":0.0,
                "end_time":1.4,
                "transcript":"ignored fallback"
              },
              "results":[
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"hello"}]
                },
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"world"}]
                },
                {
                  "type":"punctuation",
                  "attaches_to":"previous",
                  "alternatives":[{"content":"."}]
                }
              ]
            }
            """.trimIndent()
        )

        val transcript = assertIs<SpeechmaticsServerEvent.FinalTranscript>(event)
        assertEquals("hello world.", transcript.transcript)
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
