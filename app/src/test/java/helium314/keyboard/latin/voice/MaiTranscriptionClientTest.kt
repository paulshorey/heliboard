// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.*
import org.junit.Test

class MaiTranscriptionClientTest {
    @Test fun usesSpeechResourceRegionAndUniversalEndpoint() {
        assertEquals("wss://centralus.stt.speech.microsoft.com/speech/universal/v2",
            MaiTranscriptionClient.buildSpeechEndpoint("centralus"))
        assertFailsWith<IllegalArgumentException> { MaiTranscriptionClient.buildSpeechEndpoint("user:secret@host") }
    }
    @Test fun languageHintsPreserveLocaleAndAutomaticDetectionOmitsHint() {
        assertEquals("en-US", MaiTranscriptionClient.resolveLanguage("en_US", false))
        assertEquals("pt-BR", MaiTranscriptionClient.resolveLanguage("pt-BR", false))
        assertNull(MaiTranscriptionClient.resolveLanguage("en-US", true))
        assertNull(MaiTranscriptionClient.resolveLanguage("und", false))
    }
    @Test fun gatesOnlyDictationOnUnsupportedAndroidAndArchitecture() {
        assertNotNull(MaiTranscriptionClient.deviceSupportError(25, "arm64-v8a"))
        assertNotNull(MaiTranscriptionClient.deviceSupportError(35, "x86"))
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            assertNull(MaiTranscriptionClient.deviceSupportError(26, abi))
        }
    }
}
