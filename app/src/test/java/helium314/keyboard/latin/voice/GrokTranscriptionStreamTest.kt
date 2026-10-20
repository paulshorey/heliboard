// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Looper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GrokTranscriptionStreamTest {
    @Test fun realWebSocketStreamsBinaryAudioFiltersVoicesAndDrainsEofExactlyOnce() {
        val server = MockWebServer()
        val client = GrokTranscriptionClient()
        val frames = CopyOnWriteArrayList<ByteString>()
        val events = CopyOnWriteArrayList<String>()
        val texts = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"transcript.created","id":"local-session"}""")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frames.add(bytes)
                webSocket.send("""{"type":"transcript.partial","text":"Mine. Guest.","words":[{"text":"Mine.","speaker":5,"start":0,"end":0.04},{"text":"Guest.","speaker":8,"start":0.05,"end":0.09}],"start":0,"duration":0.1,"is_final":true,"speech_final":true}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                controls.add(JSONObject(text).getString("type"))
                if (controls.last() == "audio.done") {
                    webSocket.send("""{"type":"transcript.done","text":"Mine. Guest.","words":[{"text":"Mine.","speaker":5,"start":0,"end":0.04},{"text":"Guest.","speaker":8,"start":0.05,"end":0.09}],"duration":0.1}""")
                    webSocket.close(1000, null)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        server.start()
        try {
            client.streamingEndpoint = server.url("/v1/stt").toString()
            client.startStreaming("test-key", GrokTranscriptionClient.buildSessionConfig("en", false, 0.7, 5000, true,
                emptyList(), null), object : GrokTranscriptionClient.StreamingCallback {
                override fun onStreamReady() { events.add("ready") }
                override fun onHandshakeRestarted() { events.add("retry") }
                override fun onTranscriptionResult(segment: TranscriptSegment) { texts.add(segment.text) }
                override fun onInterimTranscription() { }
                override fun onServerResponse(hasTranscriptText: Boolean) { }
                override fun onSessionExpiring(timeLeftMs: Long) { }
                override fun onStreamError(error: String) { events.add("error:$error") }
                override fun onStreamClosed() { events.add("closed") }
            })
            await { "ready" in events }
            assertEquals("Bearer test-key", server.takeRequest().getHeader("Authorization"))
            assertEquals(GrokTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(3200) { 42 }, true))
            await { texts.isNotEmpty() }
            assertEquals(listOf("Mine."), texts.toList())
            assertEquals(3200, frames.single().size)
            assertTrue(client.finishStreaming())
            await { "closed" in events || events.any { it.startsWith("error:") } }
            assertEquals(listOf("ready", "closed"), events.toList())
            assertEquals(listOf("audio.done"), controls.toList())
            assertEquals(listOf("Mine."), texts.toList())
        } finally { client.cancelAll(); server.shutdown() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); Thread.sleep(5)
        }
        assertTrue(condition(), "Local WebSocket test timed out")
    }
}
