// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogVoiceDiagnosticsTest {
    @Test
    fun `voice package tags are included`() {
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceInputManager", "VOICE_STEP_1 start")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceRecorder", "Recording started")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "SonioxTranscription", "stream ready")))
    }

    @Test
    fun `latin ime voice messages are included`() {
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "LatinIME", "VOICE_STEP_4 transcription arrived in IME (3 chars)")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "LatinIME", "Cursor moved away from end while recording — discarding voice input")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "LatinIME", "Voice input state changed: RECORDING")))
    }

    @Test
    fun `unrelated latin ime messages are excluded`() {
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('I', "LatinIME", "Starting input. Cursor position = 0,0")))
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('I', "LatinIME", "onConfigurationChanged")))
    }

    @Test
    fun `other tags are excluded`() {
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('I', "Suggest", "request")))
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('I', null, "VOICE_STEP_1")))
    }

    @Test
    fun `redact raw transcript payload`() {
        val redacted = Log.redactVoiceDiagnosticMessage("VOICE raw transcript=[hello world]")
        assertEquals("VOICE raw transcript=[11 chars]", redacted)
    }

    @Test
    fun `redact api key patterns`() {
        val redacted = Log.redactVoiceDiagnosticMessage("""config api_key="secret-key-123" failed""")
        assertEquals("""config api_key=[redacted] failed""", redacted)
    }

    @Test
    fun `filterVoiceDiagnosticsLines keeps newest matching lines`() {
        val lines = listOf(
            LogLine('I', "LatinIME", "Starting input. Cursor position = 0,0"),
            LogLine('I', "VoiceInputManager", "marker-old"),
            LogLine('I', "VoiceInputManager", "marker-new-0"),
            LogLine('I', "VoiceInputManager", "marker-new-1"),
            LogLine('I', "VoiceInputManager", "marker-new-2"),
        )

        val filtered = Log.filterVoiceDiagnosticsLines(lines, maxLines = 2)
        assertEquals(2, filtered.size)
        assertEquals("marker-new-1", filtered[0].message)
        assertEquals("marker-new-2", filtered[1].message)
    }
}
