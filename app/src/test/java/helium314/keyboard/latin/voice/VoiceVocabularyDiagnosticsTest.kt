// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class VoiceVocabularyDiagnosticsTest {
    @Test fun countsRepeatedAndQuotedHintsWithoutReturningWords() {
        val diagnostics = VoiceVocabularyDiagnostics(listOf("Fine", "iPhone"), listOf("Fine"))
        val counts = diagnostics.count("A 'Fine' iPhone, then ‘Fine’ again. Finally, fine.")
        assertEquals(VoiceVocabularyDiagnostics.Counts(4, 3, 2), counts)
        assertFalse(counts.toString().contains("Fine", ignoreCase = true))
        assertFalse(counts.toString().contains("iPhone"))
    }

    @Test fun matchesLiteralPhrasesAndUnicodeWordBoundaries() {
        val diagnostics = VoiceVocabularyDiagnostics(listOf("C++", "New York", "API"), emptyList())
        assertEquals(VoiceVocabularyDiagnostics.Counts(3, 0, 0),
            diagnostics.count("C++ in New York uses API, not APIS or éAPI or API字 or my_API."))
    }

    @Test fun anOmittedVocabularyDoesNotReportAnyHintMatches() {
        assertEquals(VoiceVocabularyDiagnostics.Counts(0, 0, 0),
            VoiceVocabularyDiagnostics(emptyList(), emptyList()).count("'Fine'"))
    }
}
