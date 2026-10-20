// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptDiagnosticsTest {
    @Test fun shapesExplainSentenceBoundariesWithoutRevealingWordsOrNumbers() {
        val shape = TranscriptDiagnostics.shape("PrivateName123. about \"SecretName456?\"")
        assertTrue(shape.contains("periods=1"))
        assertTrue(shape.contains("questions=1"))
        assertTrue(shape.contains("start=upper"))
        assertTrue(shape.contains("lowerAfterStop=1"))
        assertFalse(shape.contains("Private"))
        assertFalse(shape.contains("Secret"))
        assertFalse(shape.contains("123"))
        assertFalse(shape.contains("456"))
    }

    @Test fun missingLabelsDistinguishLexicalWordsFromPunctuationBeforeFailure() {
        val words = JSONArray().put(JSONObject().put("text", "secret-keyphrase").put("start", 0).put("end", 0.2))
            .put(JSONObject().put("text", ".").put("start", 0.2).put("end", 0.2))
            .put(JSONObject().put("text", "PrivateName").put("start", 0.3).put("end", 0.4).put("speaker", 7))
        val event = JSONObject().put("type", "transcript.partial").put("is_final", true)
            .put("speech_final", true).put("text", "Secret-keyphrase. PrivateName")
            .put("words", words).put("start", 0).put("duration", 0.5).put("end_of_turn_confidence", 0.91)
        val output = GrokEventDiagnostics.describe(event, 32000)
        assertTrue(output.contains("kind=utterance"))
        assertTrue(output.contains("endConfidence=0.910"))
        assertTrue(output.contains("speakers=7:1"))
        assertTrue(output.contains("missingLabels=2 missingLexical=1 missingPunctuation=1"))
        assertTrue(output.contains("missingIntervals=0:0-200,1:200-200"))
        assertFalse(output.contains("keyphrase"))
        assertFalse(output.contains("PrivateName"))
    }

    @Test fun malformedTimingAndConfidenceNeverEchoProviderStrings() {
        val event = JSONObject().put("type", "transcript.partial").put("start", "private-token")
            .put("duration", "secret-time").put("end_of_turn_confidence", "secret-confidence")
        val output = GrokEventDiagnostics.describe(event, 0)
        assertTrue(output.contains("startMs=invalid endMs=invalid"))
        assertTrue(output.contains("endConfidence=absent"))
        assertFalse(output.contains("private-token"))
        assertFalse(output.contains("secret"))
    }
}
