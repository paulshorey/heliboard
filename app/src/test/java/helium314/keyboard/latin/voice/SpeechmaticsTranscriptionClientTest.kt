package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        assertTrue(config.getBoolean("enable_partials"))
        assertTrue(config.getBoolean("enable_entities"))
        assertEquals("enhanced", config.getString("operating_point"))
        assertFalse(config.has("conversation_config"))
        assertFalse(config.has("diarization"))

        val filterConfig = config.getJSONObject("transcript_filtering_config")
        assertTrue(filterConfig.getBoolean("remove_disfluencies"))
        assertTrue(filterConfig.has("replacements"))

        assertEquals(
            0.25,
            config.getJSONObject("punctuation_overrides").getDouble("sensitivity")
        )

        assertTrue(config.has("additional_vocab"))
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
        assertEquals("en-US", config.getString("output_locale"))
    }

    @Test
    fun buildStartRecognitionMessage_defaultsToEnUsOutputLocaleForPlainEnglish() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.5
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")

        assertEquals("en-US", config.getString("output_locale"))
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
    fun buildStartRecognitionMessage_includesDiarizationConfig() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en-US",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.5,
            diarizationEnabled = true
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")

        assertEquals("speaker", config.getString("diarization"))
        val diarConfig = config.getJSONObject("speaker_diarization_config")
        assertEquals(2, diarConfig.getInt("max_speakers"))
        assertTrue(diarConfig.getBoolean("prefer_current_speaker"))
        assertEquals(0.35, diarConfig.getDouble("speaker_sensitivity"), 1e-9)
    }

    @Test
    fun buildStartRecognitionMessage_excludesDiarizationWhenDisabled() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en-US",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.5,
            diarizationEnabled = false
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")

        assertFalse(config.has("diarization"))
        assertFalse(config.has("speaker_diarization_config"))
    }

    @Test
    fun buildStartRecognitionMessage_includesAdditionalVocab() {
        val vocab = listOf(
            SpeechmaticsTranscriptionClient.Companion.VocabEntry("HeliBoard"),
            SpeechmaticsTranscriptionClient.Companion.VocabEntry(
                "gnocchi", listOf("nyohki", "nokey")
            )
        )
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.5,
            additionalVocab = vocab,
            replacements = emptyList()
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")
        val vocabArray = config.getJSONArray("additional_vocab")

        assertEquals(2, vocabArray.length())
        assertEquals("HeliBoard", vocabArray.getString(0))
        val gnocchiObj = vocabArray.getJSONObject(1)
        assertEquals("gnocchi", gnocchiObj.getString("content"))
        assertEquals(2, gnocchiObj.getJSONArray("sounds_like").length())
    }

    @Test
    fun buildStartRecognitionMessage_includesReplacements() {
        val replacements = listOf(
            SpeechmaticsTranscriptionClient.Companion.ReplacementRule("heli board", "HeliBoard"),
            SpeechmaticsTranscriptionClient.Companion.ReplacementRule("/^[Oo]kay google$/", "OK Google")
        )
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.5,
            additionalVocab = emptyList(),
            replacements = replacements
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")
        val filterConfig = config.getJSONObject("transcript_filtering_config")
        val replacementsArray = filterConfig.getJSONArray("replacements")

        assertEquals(2, replacementsArray.length())
        assertEquals("heli board", replacementsArray.getJSONObject(0).getString("from"))
        assertEquals("HeliBoard", replacementsArray.getJSONObject(0).getString("to"))
        assertEquals("/^[Oo]kay google$/", replacementsArray.getJSONObject(1).getString("from"))
        assertEquals("OK Google", replacementsArray.getJSONObject(1).getString("to"))
    }

    @Test
    fun buildStartRecognitionMessage_permitsAllPunctuationMarks() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.55
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val overrides = payload
            .getJSONObject("transcription_config")
            .getJSONObject("punctuation_overrides")
        val permitted = overrides.getJSONArray("permitted_marks")

        assertEquals(1, permitted.length())
        assertEquals("all", permitted.getString(0))
        assertEquals(0.55, overrides.getDouble("sensitivity"), 1e-9)
    }

    @Test
    fun buildStartRecognitionMessage_omitsConversationConfigWhenEouDisabled() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 0.0,
            punctuationSensitivity = 0.55
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")

        assertFalse(config.has("conversation_config"))
    }

    @Test
    fun buildStartRecognitionMessage_includesConversationConfig() {
        val sessionConfig = SpeechmaticsTranscriptionClient.buildSessionConfig(
            languageTag = "en",
            maxDelaySeconds = 2.0,
            removeDisfluencies = false,
            endOfUtteranceSilenceTriggerSeconds = 1.5,
            punctuationSensitivity = 0.5,
            additionalVocab = emptyList(),
            replacements = emptyList()
        )
        val payload = JSONObject(
            SpeechmaticsTranscriptionClient.buildStartRecognitionMessage(sessionConfig)
        )
        val config = payload.getJSONObject("transcription_config")
        val convConfig = config.getJSONObject("conversation_config")

        assertEquals(1.5, convConfig.getDouble("end_of_utterance_silence_trigger"))
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
    fun parseServerEvent_filtersByPrimarySpeaker() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":0.0,
                "end_time":3.0,
                "transcript":"hello world goodbye"
              },
              "results":[
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"hello","speaker":"S1"}]
                },
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"world","speaker":"S1"}]
                },
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"goodbye","speaker":"S2"}]
                }
              ]
            }
            """.trimIndent(),
            primarySpeaker = "S1"
        )

        val transcript = assertIs<SpeechmaticsServerEvent.FinalTranscript>(event)
        assertEquals("hello world", transcript.transcript)
    }

    @Test
    fun parseServerEvent_includesUUSpeakerTokensWhenDiarizing() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":0.0,
                "end_time":2.0,
                "transcript":"hello world"
              },
              "results":[
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"hello","speaker":"UU"}]
                },
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"world","speaker":"S1"}]
                }
              ]
            }
            """.trimIndent(),
            primarySpeaker = "S1"
        )

        val transcript = assertIs<SpeechmaticsServerEvent.FinalTranscript>(event)
        assertEquals("hello world", transcript.transcript)
    }

    @Test
    fun parseServerEvent_returnsNullWhenAllTokensFilteredBySpeaker() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":0.0,
                "end_time":1.0,
                "transcript":"goodbye"
              },
              "results":[
                {
                  "type":"word",
                  "attaches_to":"none",
                  "alternatives":[{"content":"goodbye","speaker":"S2"}]
                }
              ]
            }
            """.trimIndent(),
            primarySpeaker = "S1"
        )

        assertNull(event)
    }

    @Test
    fun parseServerEvent_diarizationDoesNotUseMetadataTranscriptWhenResultsEmpty() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddTranscript",
              "metadata":{
                "start_time":0.0,
                "end_time":1.0,
                "transcript":"hello from everyone"
              },
              "results":[]
            }
            """.trimIndent(),
            primarySpeaker = "S1"
        )

        assertNull(event)
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
    fun parseServerEvent_parsesPartialTranscript() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddPartialTranscript",
              "metadata":{
                "transcript":"hello wor"
              }
            }
            """.trimIndent()
        )

        val partial = assertIs<SpeechmaticsServerEvent.PartialTranscript>(event)
        assertEquals("hello wor", partial.transcript)
    }

    @Test
    fun parseServerEvent_ignoresBlankPartialTranscript() {
        val event = SpeechmaticsTranscriptionClient.parseServerEvent(
            """
            {
              "message":"AddPartialTranscript",
              "metadata":{
                "transcript":"   "
              }
            }
            """.trimIndent()
        )

        assertNull(event)
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

    @Test
    fun defaultAdditionalVocab_returnsNonEmptyList() {
        val vocab = SpeechmaticsTranscriptionClient.defaultAdditionalVocab()
        assertTrue(vocab.isNotEmpty())
        assertTrue(vocab.any { it.content == "HeliBoard" })
    }

    @Test
    fun defaultReplacements_returnsNonEmptyList() {
        val replacements = SpeechmaticsTranscriptionClient.defaultReplacements()
        assertTrue(replacements.isNotEmpty())
        assertTrue(replacements.any { it.from.contains("heli board") })
    }
}
