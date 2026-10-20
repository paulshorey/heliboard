// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Looper
import java.time.Duration
import kotlin.test.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Deterministic coverage of the production sender, readiness, retries and cancellation. */
@RunWith(RobolectricTestRunner::class)
class GrokTranscriptionClientTest {
    private val client = GrokTranscriptionClient()
    private val sockets = mutableListOf<FakeSocket>()
    private val requests = mutableListOf<Request>()
    private val listeners = mutableListOf<WebSocketListener>()
    private val events = mutableListOf<String>()
    private val text = mutableListOf<String>()
    private val callback = object : GrokTranscriptionClient.StreamingCallback {
        override fun onStreamReady() { events.add("ready") }
        override fun onHandshakeRestarted() { events.add("retry") }
        override fun onTranscriptionResult(segment: TranscriptSegment) { text.add(segment.text) }
        override fun onInterimTranscription() { events.add("interim") }
        override fun onServerResponse(hasTranscriptText: Boolean) { events.add("progress:$hasTranscriptText") }
        override fun onSessionExpiring(timeLeftMs: Long) { }
        override fun onStreamError(error: String) { events.add("error:$error") }
        override fun onStreamClosed() { events.add("closed") }
    }
    private fun start(language: String? = "en-US") {
        client.socketFactory = WebSocket.Factory { request, listener ->
            requests.add(request); listeners.add(listener)
            FakeSocket(request).also { sockets.add(it) }
        }
        client.startStreaming("personal-test-key", GrokTranscriptionClient.buildSessionConfig(
            language, false, 0.7, 5000, true, listOf("McDonald"), "Already typed private paragraph."), callback)
    }
    private fun advance(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun message(payload: String, index: Int = listeners.lastIndex) {
        listeners[index].onMessage(sockets[index], payload); advance()
    }
    private fun ready() = message("""{"type":"transcript.created","id":"test-session"}""")
    private fun audio(speech: Boolean = true): GrokTranscriptionClient.AudioSendResult = client.offerAudioChunk(ByteArray(3200) { 7 }, speech)
    private fun reject(code: Int, retryAfter: String? = null) {
        val response = Response.Builder().request(requests.last()).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code).message("test").apply { retryAfter?.let { header("Retry-After", it) } }.build()
        listeners.last().onFailure(sockets.last(), java.io.IOException("do not log personal-test-key"), response)
        advance()
    }
    @After fun cleanup() { client.cancelAll() }

    @Test fun correctWireConfigurationAndHeaderAuthenticationWithoutSendingEditorParagraphs() {
        start()
        val request = requests.single()
        assertEquals("Bearer personal-test-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("personal-test-key"))
        assertEquals("pcm", request.url.queryParameter("encoding"))
        assertEquals("16000", request.url.queryParameter("sample_rate"))
        assertEquals("true", request.url.queryParameter("diarize"))
        assertEquals("0.7", request.url.queryParameter("smart_turn"))
        assertEquals("5000", request.url.queryParameter("smart_turn_timeout"))
        assertEquals("en", request.url.queryParameter("language"))
        assertFalse(request.url.toString().contains("Already"))
        assertEquals(GrokTranscriptionClient.AudioSendResult.FAILED, audio())
        assertTrue(sockets.single().sent.isEmpty())
        ready()
        assertEquals(GrokTranscriptionClient.AudioSendResult.ACCEPTED, audio())
        assertTrue(sockets.single().sent.single() is ByteString)
    }
    @Test fun localThinkingPauseDoesNotForceASemanticTurnButExplicitPauseDoes() {
        start(); ready(); audio()
        assertTrue(client.noteLocalSilence())
        assertEquals(1, sockets.single().sent.size)
        assertTrue(client.finalizeTurn())
        assertEquals("finalize", JSONObject(sockets.single().sent.last() as String).getString("type"))
        assertTrue(client.finishStreaming())
        assertEquals("audio.done", JSONObject(sockets.single().sent.last() as String).getString("type"))
        assertEquals(GrokTranscriptionClient.AudioSendResult.FAILED, audio())
    }
    @Test fun pacedAudioCannotBurstAnOldBacklogAfterAThinkingPause() {
        start(); ready()
        assertEquals(GrokTranscriptionClient.AudioSendResult.ACCEPTED, audio())
        assertEquals(GrokTranscriptionClient.AudioSendResult.ACCEPTED, audio())
        assertEquals(GrokTranscriptionClient.AudioSendResult.BACKPRESSURE, audio())
        advance(10_000)
        assertEquals(GrokTranscriptionClient.AudioSendResult.ACCEPTED, audio())
        assertEquals(GrokTranscriptionClient.AudioSendResult.ACCEPTED, audio())
        assertEquals(GrokTranscriptionClient.AudioSendResult.BACKPRESSURE, audio())
    }
    @Test fun controlsRemainBehindQueuedAudioAndBlockNewerAudioUntilSent() {
        start(); ready(); audio()
        sockets.single().queued = GrokTranscriptionClient.MAX_SOCKET_BYTES - 5
        assertTrue(client.finalizeTurn())
        assertEquals(1, sockets.single().sent.size)
        assertEquals(GrokTranscriptionClient.AudioSendResult.BACKPRESSURE, audio())
        sockets.single().queued = 0
        advance(25)
        assertEquals("finalize", JSONObject(sockets.single().sent.last() as String).getString("type"))
    }
    @Test fun blockedControlHasABoundedLifetimeEvenWhilePausedWithoutWords() {
        start(); ready(); sockets.single().queued = GrokTranscriptionClient.MAX_SOCKET_BYTES
        client.finalizeTurn(); advance(30_000)
        assertTrue(events.any { it.contains("finalization stalled") })
        assertTrue(sockets.single().cancelled)
    }
    @Test fun http503RetriesOnlyTwiceBeforeAnyAudioAndStaleAttemptCannotBecomeReady() {
        start(); reject(503); advance(500)
        assertEquals(2, requests.size)
        message("""{"type":"transcript.created"}""", 0)
        assertFalse(events.contains("ready"))
        reject(503); advance(1500)
        assertEquals(3, requests.size)
        reject(503); advance(5000)
        assertEquals(3, requests.size)
        assertEquals(1, events.count { it.startsWith("error:") })
    }
    @Test fun retryAfterOutsideOriginalStartupDeadlineDoesNotRetry() {
        start(); reject(429, "30"); advance(30_000)
        assertEquals(1, requests.size)
        assertTrue(events.any { it.contains("rate limit") })
    }
    @Test fun originalStartupBudgetStillAppliesAfterRetryAndHttpUpgrade() {
        start(); advance(11_000); reject(503); advance(500)
        assertEquals(2, requests.size)
        advance(500)
        assertTrue(events.any { it.contains("connection timed out") })
        ready()
        assertFalse(events.contains("ready"))
    }
    @Test fun authenticationAndMidstreamFailuresNeverReconnect() {
        start(); reject(401); advance(2000)
        assertEquals(1, requests.size)
        assertTrue(events.any { it.contains("API key") })
        client.cancelAll(); events.clear(); start(); ready(); audio(); reject(503); advance(2000)
        assertEquals(2, requests.size)
        assertTrue(events.any { it.startsWith("error:") })
    }
    @Test fun cancellationInvalidatesRetryAndEveryQueuedOldResult() {
        start(); reject(503); client.cancelAll(); advance(10_000)
        assertEquals(1, requests.size)
        message("""{"type":"transcript.created"}""")
        assertFalse(events.contains("ready"))
    }
    @Test fun knownBillingErrorsAreActionableWithoutEchoingProviderPayload() {
        start(); ready()
        message("""{"type":"error","message":"insufficient credits personal-test-key secret words"}""")
        assertTrue(events.any { it.contains("credits are exhausted") })
        assertFalse(events.any { it.contains("personal-test-key") || it.contains("secret words") })
    }
    @Test fun oldFinalCoverageCannotAcknowledgeANewerSpeechSuffixAlreadyInTheSocket() {
        start(); ready(); audio(); audio(); advance(100); audio()
        message("""{"type":"transcript.partial","text":"First.","words":[{"text":"First.","speaker":0,"start":0.0,"end":0.1}],"start":0.0,"duration":0.1,"is_final":true,"speech_final":true}""")
        assertEquals(listOf("First."), text)
        assertTrue(client.hasPendingSpeech(9600))
        assertFalse(client.hasPendingSpeech(3200))
    }
    @Test fun malformedOrUnattributedFinalFailsWithoutPromotingProvisionalWords() {
        start(); ready(); audio()
        message("""{"type":"transcript.partial","text":"Maybe","words":[],"start":0,"duration":0.1,"is_final":false,"speech_final":false}""")
        message("""{"type":"transcript.partial","text":"Maybe","words":[{"text":"Maybe","start":0,"end":0.1}],"start":0,"duration":0.1,"is_final":true,"speech_final":true}""")
        assertTrue(text.isEmpty())
        assertEquals(1, events.count { it.startsWith("error:") })
    }
    @Test fun missingLanguageHintDisablesNumberFormatting() {
        start("unknown")
        assertNull(requests.single().url.queryParameter("language"))
        assertNull(requests.single().url.queryParameter("format"))
    }

    private class FakeSocket(private val request: Request) : WebSocket {
        val sent = mutableListOf<Any>()
        var queued = 0L
        var cancelled = false
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean { sent.add(text); return !cancelled }
        override fun send(bytes: ByteString): Boolean { sent.add(bytes); return !cancelled }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
