// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogVoiceDiagnosticsTest {
    @Test
    fun `voice package tags are included`() {
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceInputManager", "VOICE_STEP_1 start")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceRecorder", "Recording started")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "GeminiTranscription", "stream ready")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('W', "VoiceNetwork", "default route lost")))
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

    @Test fun `voice selection checks are retained without unrelated connection traces`() {
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "RichInputConnection", "VOICE selection verification verified=false")))
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('W', "RichInputConnection", "cached text out of sync, reloading")))
    }

    @Test
    fun `other tags are excluded`() {
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('I', "Suggest", "request")))
        assertFalse(Log.isVoiceDiagnosticLine(LogLine('I', null, "VOICE_STEP_1")))
    }

    @Test
    fun `voice response lines are included`() {
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceInputManager", "VOICE_RESPONSE ok in 120ms (turn_finalize): transcript received")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('E', "VoiceInputManager", "VOICE_RESPONSE timeout after 15000ms (audio_pending): no Gemini response")))
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
    fun `redact api key in url query string`() {
        val redacted = Log.redactVoiceDiagnosticMessage(
            "opening wss://generativelanguage.googleapis.com/ws/x?key=AIzaSecret123&alt=json"
        )
        assertEquals(
            "opening wss://generativelanguage.googleapis.com/ws/x?key=[redacted]&alt=json",
            redacted
        )
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

    @Test
    fun `zero or negative line limits return no diagnostics`() {
        val lines = listOf(LogLine('E', "VoiceInputManager", "failure"))
        assertTrue(Log.filterVoiceDiagnosticsLines(lines, 0).isEmpty())
        assertTrue(Log.filterVoiceDiagnosticsLines(lines, -1).isEmpty())
        assertTrue(Log.getVoiceDiagnosticsLog(0).isEmpty())
    }

    @Test
    fun `keyboard trace volume cannot evict a voice failure`() {
        val marker = "retention test failure"
        Log.e("VoiceInputManager", marker)
        repeat(12_100) { Log.d("KeyboardParser", "geometry trace $it") }
        assertTrue(Log.getVoiceDiagnosticsLog().any { it.message == marker })
        assertFalse(Log.getLog().any { it.message == marker })
    }

    @Test
    fun `voice history is bounded even when general history wraps`() {
        repeat(600) { Log.i("VoiceRecorder", "bounded voice history $it") }
        val voice = Log.getVoiceDiagnosticsLog()
        assertEquals(Log.DEFAULT_VOICE_DIAGNOSTICS_MAX_LINES, voice.size)
        assertEquals("bounded voice history 100", voice.first().message)
        assertEquals("bounded voice history 599", voice.last().message)
    }

    @Test
    fun `debug export leads with voice diagnostics and collapses duplicate warnings`() {
        val voice = LogLine('W', "GeminiTranscription", "close code=1011 reason=prepayment credits depleted ?key=secret")
        val repeat = LogLine('W', "RichInputConnection", "cached text out of sync, reloading")
        val noise = LogLine('D', "KeyboardParser", "adding key q")
        val native = "10-06 10:39:50.174 100 100 W NativeCrash: useful native warning"
        val system = "10-06 10:39:50.174 100 100 W GeminiTranscription: duplicate close\n$native"
        val export = Log.formatDebugLogExport(listOf(noise, repeat, repeat, repeat), listOf(voice), "3.6 (3604)", system)
        assertTrue(export.startsWith("HeliBoard voice diagnostics"))
        assertTrue(export.contains("close code=1011 reason=prepayment credits depleted ?key=[redacted]"))
        assertFalse(export.contains("secret"))
        assertTrue(export.contains("[repeated 3 times]"))
        assertFalse(export.contains("adding key q"))
        assertFalse(export.contains("duplicate close"))
        assertTrue(export.contains(native))
    }
}
