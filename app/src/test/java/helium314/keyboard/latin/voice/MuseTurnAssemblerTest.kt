package helium314.keyboard.latin.voice

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class MuseTurnAssemblerTest {
    private val assembler = MuseTurnAssembler()
    private fun event(type: String, id: Int? = null, text: String? = null, label: String? = null) =
        assembler.accept(JSONObject().put("type", type).apply {
            id?.let { put("turnId", it) }; text?.let { put("transcript", it) }; label?.let { put("label", it) }
        })
    private fun start(id: Int, label: String) { event("speechStart", id); event("speaker", label = label) }
    private fun end(id: Int) { event("speechEnd", id) }
    @Test fun firstSpeechOrderSelectsSpeakerEvenWhenLaterTurnCompletesFirst() {
        start(10, "B"); end(10); start(2, "A"); end(2)
        assertTrue(event("speechComplete", 2, "Background.").isEmpty())
        val results = event("speechComplete", 10, "My first sentence.")
        assertEquals(listOf(true, false), results.map { it.selected })
        assertEquals(listOf(10, 2), results.map { it.id })
        start(99, "B"); end(99)
        assertTrue(event("speechComplete", 99, "My second sentence.").single().selected)
        assembler.verifyFinished()
    }
    @Test fun cumulativeAndFinalFlagPartialsNeverProduceEditorText() {
        start(1, "A")
        for (text in listOf("Tuesday", "Wednesday", "Wednesday at noon")) {
            assertTrue(event("transcript", text = text).isEmpty())
        }
        end(1)
        assertEquals("Wednesday at noon.", event("speechComplete", 1, "Wednesday at noon.").single().text)
    }
    @Test fun identicalSentencesInDifferentTurnsAreRetainedAndRepeatedFinalIsIgnored() {
        start(1, "A"); end(1)
        assertEquals("Yes.", event("speechComplete", 1, "Yes.").single().text)
        assertTrue(event("speechComplete", 1, "Yes.").isEmpty())
        start(2, "A"); end(2)
        assertEquals("Yes.", event("speechComplete", 2, "Yes.").single().text)
    }
    @Test fun missingOrConflictingSpeakerNeverLeaksTextOrSelectsLaterSpeaker() {
        event("speechStart", 1); end(1)
        assertTrue(event("speechComplete", 1, "Unattributed.").isEmpty())
        start(2, "B"); end(2)
        assertTrue(event("speechComplete", 2, "Later speaker.").isEmpty())
        assertFailsWith<IllegalStateException> { assembler.verifyFinished() }
    }
    @Test fun multipleDifferentLabelsInOneTurnCannotBeSafelyFiltered() {
        start(1, "A"); event("speaker", label = "B"); end(1)
        assertTrue(event("speechComplete", 1, "Mixed voices.").isEmpty())
        assertFailsWith<IllegalStateException> { assembler.verifyFinished() }
    }
    @Test fun repeatedSameLabelAndCompletionBeforeSpeechEndAreSupported() {
        start(1, "A"); event("speaker", label = "A")
        assertTrue(event("speechComplete", 1, "Done.").isEmpty())
        assertEquals("Done.", event("speechEnd", 1).single().text)
        assembler.verifyFinished()
    }
    @Test fun lateLabelCanReleaseCompletedTurnWhileItsSpanIsStillActive() {
        event("speechStart", 1)
        assertTrue(event("speechComplete", 1, "Ready.").isEmpty())
        assertTrue(event("speaker", label = "A").isEmpty())
        assertEquals("Ready.", event("speechEnd", 1).single().text)
    }
    @Test fun aConflictingLateLabelBeforeSpeechEndCannotLeakAnEarlyCompletion() {
        start(1, "A")
        assertTrue(event("speechComplete", 1, "Mixed voices.").isEmpty())
        event("speaker", label = "B"); end(1)
        assertFailsWith<IllegalStateException> { assembler.verifyFinished() }
    }
    @Test fun unfinishedWordsAreUnresolvedButNoiseWithoutWordsCanFinishNormally() {
        event("speechStart", 1); end(1); assembler.verifyFinished()
        event("speechStart", 2); event("transcript", text = "unfinished")
        assertFailsWith<IllegalStateException> { assembler.verifyFinished() }
    }
    @Test fun speakerAndPartialWithoutAnActiveTurnAreProtocolErrors() {
        assertFailsWith<IllegalStateException> { event("speaker", label = "A") }
        assertFailsWith<IllegalStateException> { event("transcript", text = "No turn") }
    }
    @Test fun unresolvedTurnQueueIsBounded() {
        repeat(64) { event("speechStart", it); end(it) }
        assertFailsWith<IllegalStateException> { event("speechStart", 65) }
    }
    @Test fun onlyEndedRecognizedTurnsStartFinalWaits() {
        start(1, "A"); event("transcript", text = "Still speaking")
        assertTrue(assembler.pendingFinalIds().isEmpty())
        end(1); assertEquals(setOf(1), assembler.pendingFinalIds())
        event("speechComplete", 1, "Still speaking.")
        assertTrue(assembler.pendingFinalIds().isEmpty())
    }
}
