package helium314.keyboard.latin.voice

import android.os.Looper
import java.time.Duration
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class MuseTranscriptionClientTest {
    private var client = MuseTranscriptionClient()
    private lateinit var transport: WebSocketListener
    private val socket = FakeSocket()
    private val texts = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var closed = false
    private var ready = false
    private var settled = 0
    private val config = MuseTranscriptionClient.SessionConfig(24000, listOf("English"), listOf("Anaya"))
    @Before fun setup() {
        start(config)
    }
    private fun start(sessionConfig: MuseTranscriptionClient.SessionConfig) {
        client.socketFactory = WebSocket.Factory { _, listener -> transport = listener; socket }
        client.startStreaming("test-secret", sessionConfig, object : MuseTranscriptionClient.StreamingCallback {
            override fun onStreamReady() { ready = true }
            override fun onTranscriptionResult(segment: TranscriptSegment) { texts.add(segment.text) }
            override fun onInterimTranscription() { }
            override fun onServerResponse(hasTranscriptText: Boolean) { if (hasTranscriptText) settled++ }
            override fun onStreamError(error: String) { errors.add(error) }
            override fun onStreamClosed() { closed = true }
        })
    }
    @After fun finish() { client.cancelAll() }
    private fun message(text: String) { transport.onMessage(socket, text); shadowOf(Looper.getMainLooper()).idle() }
    private fun ack() = message("""{"sessionId":"test-session"}""")
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun event(type: String, id: Int = 1, text: String = "Hello.", label: String = "A") = message(
        JSONObject().put("type", type).put("turnId", id).put("transcript", text).put("label", label)
            .put("final", true).put("audioProcessedMs", 0).toString())
    @Test fun endpointingNeedsNoSpeakerButStillWaitsForCompletedTurnAndBoundary() {
        start(config.copy(mode = MuseTranscriptionClient.Mode.ENDPOINTING))
        transport.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ENDPOINTING", JSONObject(socket.controls.single()).getString("mode"))
        ack(); event("speechStart"); event("transcript")
        assertTrue(texts.isEmpty())
        event("speechComplete"); assertTrue(texts.isEmpty())
        event("speechEnd")
        event("speechStart", 2); event("speechEnd", 2); event("speechComplete", 2, "Another voice.")
        assertEquals(listOf("Hello.", "Another voice."), texts)
        assertTrue(client.finishStreaming())
        transport.onClosed(socket, 1000, ""); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(closed); assertTrue(errors.isEmpty())
    }
    @Test fun restartingProtectedModeCannotInheritEndpointingAttributionRules() {
        start(config.copy(mode = MuseTranscriptionClient.Mode.ENDPOINTING))
        ack(); event("speechStart"); event("speechEnd"); event("speechComplete")
        start(config); ack(); event("speechStart"); event("speechEnd"); event("speechComplete")
        advance(30_001)
        assertEquals(listOf("Hello."), texts)
        assertEquals(1, errors.size)
    }
    @Test fun handshakeUsesBodyCredentialDiarizationAndNamedLanguageHints() {
        transport.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build())
        shadowOf(Looper.getMainLooper()).idle()
        val json = JSONObject(socket.controls.single())
        assertEquals("Bearer test-secret", json.getJSONObject("authorization").getString("accessToken"))
        assertEquals("DIARIZATION", json.getString("mode"))
        assertEquals("PCM_24KHZ", json.getString("audioEncoding"))
        assertEquals("CUMULATIVE", json.getString("partialMode"))
        assertEquals("English", json.getJSONArray("languageBias").getString(0))
        assertFalse(ready)
        ack(); assertTrue(ready)
    }
    @Test fun startupAndRealTimePacingRetainAudioRatherThanBursting() {
        assertEquals(MuseTranscriptionClient.AudioSendResult.FAILED, client.offerAudioChunk(ByteArray(4800)))
        ack()
        assertEquals(MuseTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(4800)))
        assertEquals(MuseTranscriptionClient.AudioSendResult.BACKPRESSURE, client.offerAudioChunk(ByteArray(4800)))
        advance(100)
        assertEquals(MuseTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(4800)))
        assertEquals(2, socket.audio.size)
    }
    @Test fun processedAudioLeadIsBoundedEvenWhenSocketDrains() {
        ack()
        repeat(20) { assertEquals(MuseTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(4800))); advance(100) }
        assertEquals(MuseTranscriptionClient.AudioSendResult.BACKPRESSURE, client.offerAudioChunk(ByteArray(4800)))
        message("""{"type":"audioProgress","audioProcessedMs":2000}""")
        assertEquals(MuseTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(4800)))
    }
    @Test fun onlyCompletedSelectedSpeakerTextIsInsertedAndDiscardedTurnsSettle() {
        ack(); event("speechStart"); event("transcript"); assertTrue(texts.isEmpty())
        event("speaker"); event("speechEnd"); event("speechComplete")
        event("speechStart", 2); event("speaker", 2, label = "B"); event("speechEnd", 2)
        event("speechComplete", 2, "Background.")
        assertEquals(listOf("Hello."), texts); assertEquals(2, settled)
        assertFalse(client.hasPendingTurns())
    }
    @Test fun eofWaitsForLateFinalAndNormalCloseAndNeverSendsMoreAudio() {
        ack(); event("speechStart"); event("speaker"); event("transcript")
        assertTrue(client.finishStreaming()); assertFalse(closed)
        assertEquals("{\"type\":\"endStream\"}", socket.controls.last())
        assertEquals(MuseTranscriptionClient.AudioSendResult.FAILED, client.offerAudioChunk(ByteArray(4800)))
        event("speechEnd"); event("speechComplete")
        event("transcript", text = "") // Empty final flush observed on live Muse EOF.
        transport.onClosed(socket, 1000, ""); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(closed); assertEquals(listOf("Hello."), texts); assertTrue(errors.isEmpty())
    }
    @Test fun missingAttributionOnNormalCloseIsFailureWithoutText() {
        ack(); event("speechStart"); event("speechEnd"); event("speechComplete")
        client.finishStreaming(); transport.onClosed(socket, 1000, ""); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, errors.size); assertFalse(closed); assertTrue(texts.isEmpty())
    }
    @Test fun longSpeechWithProgressCanContinueButSpeechEndStartsFixedFinalDeadline() {
        ack(); event("speechStart"); event("speaker"); event("transcript"); advance(65_000)
        assertTrue(errors.isEmpty())
        event("speechEnd"); advance(20_000); event("audioProgress"); event("transcript"); advance(10_001)
        assertEquals(1, errors.size); assertTrue(texts.isEmpty())
    }
    @Test fun cancelInvalidatesPreviouslyPostedFramesAndLaterErrors() {
        ack(); transport.onMessage(socket, "not json"); client.cancelAll()
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(errors.isEmpty())
    }
    @Test fun invalidServicePayloadAndErrorsNeverExposeTranscriptOrKey() {
        ack(); message("""{"type":"error","message":"Bearer test-secret raw private transcript"}""")
        assertEquals(1, errors.size)
        assertFalse(errors.single().contains("test-secret")); assertFalse(errors.single().contains("private transcript"))
    }
    @Test fun malformedKnownEventIsTerminalButUnknownEventsAreIgnored() {
        ack(); message("""{"type":"future"}"""); assertTrue(errors.isEmpty())
        message("""{"type":"speechComplete"}"""); assertEquals(1, errors.size)
    }
    @Test fun formatAndLanguageFallbacksAreExplicit() {
        assertEquals("PCM_16KHZ", JSONObject(MuseTranscriptionClient.handshake("k", config.copy(sampleRate = 16000))).getString("audioEncoding"))
        assertEquals("English", MuseTranscriptionClient.resolveLanguageCode("en_US"))
        assertEquals(null, MuseTranscriptionClient.resolveLanguageCode("xx"))
        assertFailsWith<IllegalArgumentException> { MuseTranscriptionClient.handshake("k", config.copy(sampleRate = 48000)) }
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
