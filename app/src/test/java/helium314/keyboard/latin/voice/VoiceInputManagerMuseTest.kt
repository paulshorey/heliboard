// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.prefs
import java.time.Duration
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.*

/** Real client/manager coordination with a fake microphone and socket, no API account. */
@RunWith(RobolectricTestRunner::class)
class VoiceInputManagerMuseTest {
    private val client = MuseTranscriptionClient()
    private lateinit var manager: VoiceInputManager
    private lateinit var capture: VoiceRecorder.RecordingCallback
    private lateinit var transport: WebSocketListener
    private val socket = FakeSocket()
    private val inserted = mutableListOf<String>()
    private val markers = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        context.prefs().edit().clear().putInt(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS, 300).commit()
        TranscriptionPreferences.writeMuseApiKey(context, "private-test-key")
        val recorder = Mockito.mock(VoiceRecorder::class.java) { call ->
            when (call.method.name.substringBefore('$')) {
                "hasRecordPermission", "startRecording" -> true
                "getSampleRate" -> 24000
                "setCallback" -> { capture = call.arguments[0] as VoiceRecorder.RecordingCallback; null }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        client.socketFactory = WebSocket.Factory { _, listener -> transport = listener; socket }
        manager = VoiceInputManager(context, recorder, client, object : VoiceNetworkMonitor {
            override fun start(onUnavailable: () -> Unit) = true
            override fun stop() { }
        })
        manager.setListener(object : VoiceInputManager.VoiceInputListener {
            override fun onStateChanged(state: VoiceInputManager.State) { }
            override fun onTranscriptionResult(text: String, attachesToPrevious: Boolean): Boolean {
                inserted.add(text); return true
            }
            override fun onTranscriptionInterrupted(marker: String): Boolean { markers.add(marker); return true }
            override fun onProcessingStarted() { }
            override fun onProcessingIdle() { }
            override fun onPendingProcessingCancelled() { }
            override fun onError(error: String) { errors.add(error) }
            override fun onPermissionRequired() { error("Unexpected permission request") }
        })
    }
    @After fun cleanup() { manager.cancelRecording(); TranscriptionPreferences.writeMuseApiKey(context, "") }

    private fun message(json: JSONObject) {
        transport.onMessage(socket, json.toString())
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun event(type: String, id: Int = 1, progress: Int = 100, text: String = "", label: String = "A") = message(
        JSONObject().put("type", type).put("turnId", id).put("audioProcessedMs", progress)
            .put("transcript", text).put("label", label))
    private fun start() {
        assertTrue(manager.startRecording())
        transport.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("DIARIZATION", JSONObject(socket.controls.single()).getString("mode"))
        message(JSONObject().put("sessionId", "private-session"))
        capture.onAudioChunk(ByteArray(4800), containsSpeech = true) // Local RMS false positive.
        assertEquals(1, socket.audio.size)
    }
    private fun stopAndClose() {
        manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("endStream", JSONObject(socket.controls.last()).getString("type"))
        transport.onClosed(socket, 1000, "")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.isIdle)
    }
    private fun assertNoInterruption() {
        assertTrue(inserted.isEmpty())
        assertTrue(markers.isEmpty(), "A processed wordless turn must not produce an interruption marker")
        assertTrue(errors.isEmpty())
    }
    @Test fun processedWordlessNoiseClosesWithoutMarkerInProtectedMode() {
        start(); event("speechStart"); event("speechEnd")
        stopAndClose(); assertNoInterruption()
    }
    @Test fun retiredExperimentalPreferencesCannotChangeTheWireMode() {
        context.prefs().edit().putBoolean("muse_responsive_dictation", true)
            .putBoolean("muse_timing_diagnostics", true).commit()
        start(); event("speechStart"); event("speechEnd")
        stopAndClose(); assertNoInterruption()
    }
    @Test fun capturePreservesQuietPcmAndOnlyCompletedFirstSpeakerPunctuationReachesTheEditor() {
        start()
        val quiet = ByteArray(4800) { (it % 7).toByte() }
        val speech = ByteArray(4800) { (it % 113).toByte() }
        capture.onAudioChunk(quiet, containsSpeech = false)
        capture.onAudioChunk(speech, containsSpeech = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(225))
        assertEquals(3, socket.audio.size)
        assertContentEquals(quiet, socket.audio[1].toByteArray())
        assertContentEquals(speech, socket.audio[2].toByteArray())
        event("speechStart", progress = 300); event("speaker", progress = 300)
        event("transcript", progress = 300, text = "first sentence is this clear yes")
        event("speechEnd", progress = 300)
        assertTrue(inserted.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        event("speechComplete", progress = 300, text = "First sentence. Is this clear? Yes!")
        event("speechStart", id = 2, progress = 300)
        event("speaker", id = 2, progress = 300, label = "B")
        event("speechEnd", id = 2, progress = 300)
        event("speechComplete", id = 2, progress = 300, text = "Guest voice.")
        event("speechStart", id = 3, progress = 300); event("speaker", id = 3, progress = 300)
        event("speechEnd", id = 3, progress = 300)
        event("speechComplete", id = 3, progress = 300, text = "Back again.")
        stopAndClose()
        assertEquals(listOf("First sentence. Is this clear? Yes!", "Back again."), inserted)
        assertTrue(markers.isEmpty()); assertTrue(errors.isEmpty())
    }
    @Test fun wordlessNoiseRetiredBeforeStopStillSettlesAtVerifiedClose() {
        start(); event("speechStart"); event("speechEnd")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30_001))
        assertTrue(manager.isRecording)
        stopAndClose(); assertNoInterruption()
    }
    @Test fun missingRecognizedFinalStillInterruptsInsteadOfAcknowledgingClosure() {
        start(); event("speechStart"); event("transcript", text = "Unfinished words"); event("speechEnd")
        stopAndClose()
        assertTrue(inserted.isEmpty()); assertEquals(1, markers.size); assertEquals(1, errors.size)
    }
    @Test fun unprocessedWordlessNoiseStillInterrupts() {
        start(); event("speechStart", progress = 0); event("speechEnd", progress = 0)
        stopAndClose()
        assertTrue(inserted.isEmpty()); assertEquals(1, markers.size); assertEquals(1, errors.size)
    }
    @Test fun anUnprocessedSuffixPreservesTheInsertedPrefixAndReportsInterruption() {
        start(); event("speechStart"); event("speaker"); event("speechEnd")
        event("speechComplete", text = "Confirmed prefix.")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        capture.onAudioChunk(ByteArray(4800), containsSpeech = true)
        event("speechStart", id = 2); event("speechEnd", id = 2)
        stopAndClose()
        assertEquals(listOf("Confirmed prefix."), inserted)
        assertEquals(1, markers.size); assertEquals(1, errors.size)
    }
    private class FakeSocket : WebSocket {
        val controls = mutableListOf<String>()
        val audio = mutableListOf<ByteString>()
        override fun request() = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { controls.add(text); return true }
        override fun send(bytes: ByteString): Boolean { audio.add(bytes); return true }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { }
    }
}
