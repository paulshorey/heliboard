// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LogVoiceDiagnosticsTest {
    @Test
    fun `voice package tags are included`() {
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceInputManager", "VOICE_STEP_1 start")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceRecorder", "Recording started")))
        assertTrue(Log.isVoiceDiagnosticLine(LogLine('I', "VoiceMicrophoneRouter", "VOICE microphone ready type=7")))
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
    fun `zero or negative line limits return no diagnostics`() {
        assertTrue(Log.getVoiceDiagnosticsLog(0).isEmpty())
        assertTrue(Log.getVoiceDiagnosticsLog(-1).isEmpty())
    }

    @Test
    fun `keyboard trace volume cannot evict a voice failure`() {
        val marker = "retention test failure"
        Log.e("VoiceInputManager", marker)
        repeat(12_100) { Log.i("KeyboardParser", "geometry trace $it") }
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
        val export = Log.formatDiagnosticReport(listOf(noise, repeat, repeat, repeat), listOf(voice), "3.6 (3604)", system)
        assertTrue(export.startsWith("HeliBoard diagnostic report"))
        assertTrue(export.contains("close code=1011 reason=prepayment credits depleted ?key=[redacted]"))
        assertFalse(export.contains("secret"))
        assertTrue(export.contains("[repeated 3 times]"))
        assertFalse(export.contains("adding key q"))
        assertFalse(export.contains("duplicate close"))
        assertTrue(export.contains(native))
    }

    @Test fun `debug traces require explicit per-tag opt in`() {
        val tag = "OptInDiagnosticsTest"
        ShadowLog.setLoggable(tag, android.util.Log.INFO)
        Log.d(tag, "hidden debug")
        Log.v(tag, "hidden verbose")
        assertFalse(Log.getLog().any { it.tag == tag })
        assertTrue(ShadowLog.getLogsForTag(tag).isEmpty())
        ShadowLog.setLoggable(tag, android.util.Log.DEBUG)
        Log.d(tag, "enabled debug")
        assertTrue(Log.getLog().any { it.tag == tag && it.message == "enabled debug" })
        assertTrue(ShadowLog.getLogsForTag(tag).any { it.msg == "enabled debug" })
    }

    @Test
    @Config(sdk = [21, 25, 35], application = android.app.Application::class)
    fun `long trace tags use the legacy property name on old Android`() {
        val tag = "LongKeyboardDiagnosticsTagForCompatibility"
        val propertyTag = if (android.os.Build.VERSION.SDK_INT < 26) tag.take(23) else tag
        ShadowLog.setLoggable(propertyTag, android.util.Log.VERBOSE)
        Log.d(tag, "compatible debug")
        Log.v(tag, "compatible verbose")
        assertTrue(Log.getLog().any { it.tag == tag && it.message == "compatible debug" })
        assertTrue(Log.getLog().any { it.tag == tag && it.message == "compatible verbose" })
    }
}
