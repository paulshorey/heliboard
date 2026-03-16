// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTranscriptionPreprocessorTest {
    @Test
    fun `lowercases short chunk and removes trailing period`() {
        val processed = VoiceTranscriptionPreprocessor.preprocessChunk("Hello There. ")

        assertEquals("hello there ", processed)
    }

    @Test
    fun `removes trailing period at end of short chunk`() {
        val processed = VoiceTranscriptionPreprocessor.preprocessChunk("Thanks.")

        assertEquals("thanks", processed)
    }

    @Test
    fun `converts spoken special characters to symbols`() {
        val processed = VoiceTranscriptionPreprocessor.preprocessChunk(
            "open curly bracket hashtag backtick close curly bracket"
        )

        assertEquals("{ # ` }", processed)
    }

    @Test
    fun `keeps long chunk casing but still replaces spoken punctuation`() {
        val processed = VoiceTranscriptionPreprocessor.preprocessChunk(
            "Please Type Colon after the label and then say Question Mark"
        )

        assertEquals("Please Type : after the label and then say ?", processed)
    }

    @Test
    fun `does not replace character names inside larger words`() {
        val processed = VoiceTranscriptionPreprocessor.preprocessChunk(
            "The periodic review should stay periodic for this sentence"
        )

        assertEquals(
            "The periodic review should stay periodic for this sentence",
            processed
        )
    }
}
