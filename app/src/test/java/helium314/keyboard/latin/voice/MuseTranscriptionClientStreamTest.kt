package helium314.keyboard.latin.voice

import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.*

/** Real OkHttp frames against a local server; no provider account or network service. */
@RunWith(RobolectricTestRunner::class)
class MuseTranscriptionClientStreamTest {
    private val server = MockWebServer()
    private val client = MuseTranscriptionClient()
    private val events = CopyOnWriteArrayList<String>()
    private val controls = CopyOnWriteArrayList<String>()
    private val audio = CopyOnWriteArrayList<ByteString>()
    @After fun cleanup() { client.cancelAll(); server.shutdown() }
    private fun start(listener: WebSocketListener) {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener)); server.start()
        client.streamingEndpoint = server.url("/v1/asr/realtime").toString().replaceFirst("http", "ws")
        client.startStreaming("private-test-key", MuseTranscriptionClient.SessionConfig(16000, listOf("English"), emptyList()),
            object : MuseTranscriptionClient.StreamingCallback {
                override fun onStreamReady() { events.add("ready") }
                override fun onTranscriptionResult(segment: TranscriptSegment) { events.add(segment.text) }
                override fun onInterimTranscription() { events.add("interim") }
                override fun onServerResponse(hasTranscriptText: Boolean) { }
                override fun onStreamError(error: String) { events.add("error:$error") }
                override fun onStreamClosed() { events.add("closed") }
            })
    }
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Local socket condition timed out; events=$events")
    }
    @Test fun bodyHandshakeBinaryAudioAndLateFinalDrainUseActualWebSocket() {
        start(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                controls.add(text)
                if (JSONObject(text).optString("type") == "endStream") {
                    for (event in listOf(
                        """{"type":"speechStart","turnId":5,"audioProcessedMs":0}""",
                        """{"type":"transcript","transcript":"hello","final":true,"audioProcessedMs":50}""",
                        """{"type":"speaker","label":"A","audioProcessedMs":60}""",
                        """{"type":"speechEnd","turnId":5,"audioProcessedMs":100}""",
                        """{"type":"speechComplete","turnId":5,"transcript":"Hello.","audioProcessedMs":100}""")) webSocket.send(event)
                    webSocket.close(1000, null)
                } else webSocket.send("""{"sessionId":"local"}""")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { audio.add(bytes) }
        })
        awaitUntil { events.contains("ready") }
        val request = server.takeRequest()
        assertEquals("/v1/asr/realtime", request.path)
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals("Bearer private-test-key", JSONObject(controls.first()).getJSONObject("authorization").getString("accessToken"))
        assertEquals(MuseTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(3200) { 7 }))
        assertTrue(client.finishStreaming())
        awaitUntil { events.contains("closed") }
        assertEquals(1, audio.size); assertEquals(3200, audio.single().size)
        assertEquals(listOf("ready", "interim", "Hello.", "closed"), events.toList())
        assertEquals("endStream", JSONObject(controls.last()).getString("type"))
    }
    @Test fun aRejectedHandshakeNeverUploadsAudioOrReconnects() {
        start(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                controls.add(text)
                webSocket.send("""{"type":"error","message":"private-test-key must never be logged"}""")
                webSocket.close(1008, null)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { audio.add(bytes) }
        })
        awaitUntil { events.any { it.startsWith("error:") } }
        assertEquals(1, server.requestCount)
        assertTrue(audio.isEmpty())
        assertFalse(events.any { it.contains("private-test-key") })
        assertFalse(events.contains("ready"))
    }
}
