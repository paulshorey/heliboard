// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GrokTranscriptAssemblerTest {
    private val assembler = GrokTranscriptAssembler()
    private fun word(text: String, speaker: Int?, start: Double, end: Double) = JSONObject()
        .put("text", text).put("start", start).put("end", end).apply { speaker?.let { put("speaker", it) } }
    private fun event(vararg words: JSONObject, start: Double = 0.0, duration: Double = 1.0,
        final: Boolean = true, utterance: Boolean = true, done: Boolean = false) = JSONObject()
        .put("type", if (done) "transcript.done" else "transcript.partial")
        .put("text", words.joinToString(" ") { it.getString("text") }).put("words", JSONArray(words.toList()))
        .put("is_final", final).put("speech_final", utterance).put("start", start).put("duration", duration)
    private fun accept(event: JSONObject) = assembler.accept(event, 320_000)
    private fun texts(event: JSONObject) = accept(event).segments.map { it.text }

    @Test fun firstFinalizedVoiceIsSelectedAndOtherVoicesAreFilteredWordByWord() {
        val result = accept(event(word("My", 7, 0.0, 0.2), word("words.", 7, 0.2, 0.4),
            word("Guest!", 0, 0.4, 0.6), word("Still mine.", 7, 0.6, 0.9)))
        assertEquals(listOf("My words.", "Still mine."), result.segments.map { it.text })
        assertEquals(1, result.filteredWords)
        assertEquals(emptyList(), texts(event(word("Guest again.", 0, 1.0, 1.5), start = 1.0)))
    }
    @Test fun interimCannotSelectASpeakerOrEnterTheEditor() {
        assertTrue(texts(event(word("Wrong", 9, 0.0, 0.2), final = false)).isEmpty())
        assertEquals(listOf("Right."), texts(event(word("Right.", 2, 0.0, 0.5))))
    }
    @Test fun chunkFinalWaitsForCompleteUtteranceAndReconcilesTheStitchedResult() {
        assertTrue(texts(event(word("Hello", 0, 0.0, 0.3), duration = 0.5, utterance = false)).isEmpty())
        assertTrue(assembler.hasPendingWords)
        assertEquals(listOf("Hello world."), texts(event(word("Hello", 0, 0.0, 0.3), word("world.", 0, 0.5, 0.9))))
        assertFalse(assembler.hasPendingWords)
    }
    @Test fun repeatedUtteranceAndWholeSessionEofDoNotDuplicateWords() {
        val first = event(word("Yes.", 0, 0.0, 0.4))
        assertEquals(listOf("Yes."), texts(first))
        assertTrue(texts(first).isEmpty())
        assertTrue(texts(event(word("Yes.", 0, 0.0, 0.4), done = true)).isEmpty())
    }
    @Test fun intentionallyRepeatedPhraseAtANewAudioPositionIsPreserved() {
        assertEquals(listOf("Yes."), texts(event(word("Yes.", 0, 0.0, 0.4))))
        assertEquals(listOf("Yes."), texts(event(word("Yes.", 0, 1.0, 1.4), start = 1.0)))
    }
    @Test fun emptyEofCanReleaseAttributedChunkFinalsButNeverInterims() {
        assertTrue(texts(event(word("Unfinished", 0, 0.0, 0.5), utterance = false)).isEmpty())
        assertEquals(listOf("Unfinished"), texts(event(done = true)))
        val other = GrokTranscriptAssembler()
        other.accept(event(word("Provisional", 0, 0.0, 0.8), final = false), 32000)
        assertTrue(other.accept(event(done = true), 32000).segments.isEmpty())
    }
    @Test fun finalMissingAttributionNeverFallsBackToAllSpeakers() {
        assertFailsWith<IllegalArgumentException> { texts(event(word("Unknown", null, 0.0, 0.4))) }
    }
    @Test fun chunkAttributionCanBeResolvedBeforeInsertion() {
        assertTrue(texts(event(word("Hello", null, 0.0, 0.4), utterance = false)).isEmpty())
        assertEquals(listOf("Hello."), texts(event(word("Hello.", 3, 0.0, 0.4))))
    }
    @Test fun changingAlreadyInsertedAttributionIsReportedRatherThanRewritten() {
        texts(event(word("Hello.", 1, 0.0, 0.4)))
        assertFailsWith<IllegalArgumentException> { texts(event(word("Hello.", 2, 0.0, 0.4), done = true)) }
    }
    @Test fun oldFinalCannotCertifyNewerSubmittedSpeech() {
        accept(event(word("Old.", 0, 0.0, 0.4), duration = 0.5))
        assertEquals(16000, assembler.settledBytes)
    }
    @Test fun staleInterimCannotReviveSettledSpeechOrExtendLiveness() {
        accept(event(word("Done.", 0, 0.0, 0.4), duration = 0.5))
        val stale = accept(event(word("Done", 0, 0.0, 0.4), duration = 0.5, final = false))
        assertFalse(stale.progressed)
        assertFalse(assembler.hasPendingWords)
    }
    @Test fun noAttributedWordsForNonemptyFinalIsAProtocolFailure() {
        assertFailsWith<IllegalArgumentException> { texts(event().put("text", "Missing words")) }
    }
    @Test fun futureAndInvalidTimingIsRejected() {
        assertFailsWith<IllegalArgumentException> { assembler.accept(event(word("Future", 0, 0.0, 0.5)), 100) }
        assertFailsWith<IllegalArgumentException> { texts(event(word("Reverse", 0, 0.5, 0.1))) }
    }
    @Test fun laterSpeakerCannotBeSelectedWhileEarlierRecognizedSpeechIsUnresolved() {
        accept(event(word("First", 4, 0.0, 0.4), final = false, duration = 0.5))
        assertFailsWith<IllegalArgumentException> {
            texts(event(word("Later", 8, 1.0, 1.4), start = 1.0))
        }
    }
    @Test fun punctuationAndWrittenNumbersSurviveWordReconstruction() {
        assertEquals("The price is $25. Really?", GrokTranscriptAssembler.joinWords(listOf("The", "price", "is", "$25.", "Really?")))
        assertEquals("Hello, world!", GrokTranscriptAssembler.joinWords(listOf("Hello", ",", "world", "!")))
        assertEquals("你好。", GrokTranscriptAssembler.joinWords(listOf("你", "好", "。")))
    }
    @Test fun nativePunctuationCasingAndDisfluenciesAreNotRewritten() {
        assertNull(TranscriptPostProcessor.processGrokParagraph("Um, I said Tuesday — actually Wednesday."))
        assertEquals("Hello,", TranscriptPostProcessor.processGrokParagraph("Hello. Comma."))
    }

    @Test fun completeSingleSpeakerUtterancePreservesExactProviderSpacingAndQuotes() {
        assertEquals(listOf("He said, “Yes!”"), texts(event(word("He", 0, 0.0, 0.1),
            word("said,", 0, 0.1, 0.2), word("“Yes!”", 0, 0.2, 0.5)).put("text", "He said, “Yes!”")))
    }
}
