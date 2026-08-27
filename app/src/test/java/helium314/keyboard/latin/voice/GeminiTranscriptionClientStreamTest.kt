package helium314.keyboard.latin.voice

import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * End-to-end WebSocket lifecycle tests for [GeminiTranscriptionClient], driven by
 * a local server that speaks the Gemini Live protocol.
 *
 * These exercise the parts that only exist once real frames move over a real
 * socket: waiting for `setupComplete` before releasing audio, sending audio as
 * base64 JSON text frames, surfacing `goAway`, finalizing the last turn on stop,
 * and — most importantly — falling back to a lower `setup` tier when the server
 * rejects the payload with close code 1007. A regression in that fallback would
 * leave voice input permanently dead, which is exactly the failure a live
 * integration test would catch too late.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiTranscriptionClientStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiTranscriptionClient
    private val serverReceived = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()
    private val transcripts = CopyOnWriteArrayList<TranscriptSegment>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        GeminiTranscriptionClient.streamingEndpoint =
            server.url("/ws/BidiGenerateContent").toString().replaceFirst("http", "ws")
        GeminiTranscriptionClient.negotiatedSetupTier =
            GeminiTranscriptionClient.Companion.SetupTier.FULL
        client = GeminiTranscriptionClient()
    }

    @After
    fun tearDown() {
        client.cancelAll()
        server.shutdown()
        GeminiTranscriptionClient.streamingEndpoint = GeminiTranscriptionClient.STREAMING_URL_BASE
        GeminiTranscriptionClient.negotiatedSetupTier =
            GeminiTranscriptionClient.Companion.SetupTier.FULL
    }

    @Test
    fun completesTheHappyPathFromSetupToFinalTranscript() {
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
                if (text.contains("\"setup\"")) {
                    webSocket.send("""{"setupComplete":{}}""")
                }
            }
        })

        startClient()
        awaitUntil { events.contains("ready") }

        // The API key belongs in the query string, and the first frame must be setup.
        val handshake = server.takeRequest()
        assertTrue(handshake.path!!.contains("key=test-key"))
        assertTrue(serverReceived.first().contains("\"setup\""))

        assertTrue(client.sendAudioChunk(ByteArray(320) { 7 }))
        // Matched on "audio" rather than "realtimeInput", which also appears in
        // the setup frame as realtimeInputConfig.
        awaitUntil { serverReceived.any { it.contains("\"audio\"") } }
        val audioFrame = JSONObject(serverReceived.last { it.contains("\"audio\"") })
            .getJSONObject("realtimeInput")
            .getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audioFrame.getString("mimeType"))

        currentServerSocket!!.send(
            """{"serverContent":{"interimInputTranscription":{"text":"hello wor"}}}"""
        )
        currentServerSocket!!.send(
            """{"serverContent":{"inputTranscription":{"text":"Hello world."}}}"""
        )
        awaitUntil { transcripts.isNotEmpty() }

        assertEquals("Hello world.", transcripts.single().text)
        // The interim hypothesis must never reach the editor.
        assertEquals(1, transcripts.size)
    }

    @Test
    fun holdsAudioUntilSetupCompleteArrives() {
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
            }
        })

        startClient()
        awaitUntil { serverReceived.isNotEmpty() }

        // Setup was sent but never acknowledged, so audio must be refused rather
        // than raced ahead of the session configuration.
        assertEquals(false, client.sendAudioChunk(ByteArray(320)))
        assertEquals(false, events.contains("ready"))
    }

    @Test
    fun retriesWithALowerSetupTierWhenTheServerRejectsTheSchema() {
        // First connection: reject the richest payload the way the Live API does
        // for an unsupported setup field. Second connection: accept.
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
                webSocket.close(
                    GeminiTranscriptionClient.CLOSE_CODE_INVALID_ARGUMENT,
                    "Invalid JSON payload received. Unknown name \"systemInstruction\""
                )
            }
        })
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
                webSocket.send("""{"setupComplete":{}}""")
            }
        })

        startClient()
        awaitUntil { events.contains("ready") }

        assertEquals(2, serverReceived.size)
        val rejected = JSONObject(serverReceived[0]).getJSONObject("setup")
        val accepted = JSONObject(serverReceived[1]).getJSONObject("setup")
        assertTrue(rejected.has("systemInstruction"))
        assertEquals(false, accepted.has("systemInstruction"))
        // The working tier is remembered so the next session starts there.
        assertEquals(
            GeminiTranscriptionClient.Companion.SetupTier.NO_SYSTEM_INSTRUCTION,
            GeminiTranscriptionClient.negotiatedSetupTier
        )
        // The retry must be invisible to the IME: no error, just a ready stream.
        assertEquals(listOf("ready"), events.filter { it != "response" })
    }

    @Test
    fun reportsAnInvalidKeyInsteadOfRetryingTheSchema() {
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
                webSocket.close(
                    GeminiTranscriptionClient.CLOSE_CODE_INVALID_ARGUMENT,
                    "API key not valid. Please pass a valid API key."
                )
            }
        })

        startClient()
        awaitUntil { events.any { it.startsWith("error:") } }

        assertEquals(1, serverReceived.size)
        assertTrue(
            events.any { it == "error:Invalid Gemini API key. Please check Settings." },
            "unexpected events: $events"
        )
    }

    @Test
    fun surfacesGoAwaySoTheSessionCanRotate() {
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
                webSocket.send("""{"setupComplete":{}}""")
                webSocket.send("""{"goAway":{"timeLeft":"20s"}}""")
            }
        })

        startClient()
        awaitUntil { events.any { it.startsWith("expiring:") } }

        assertTrue(events.contains("expiring:20000"), "unexpected events: $events")
    }

    @Test
    fun finalizesTheLastTurnBeforeClosingOnStop() {
        enqueueServer(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceived.add(text)
                if (text.contains("\"setup\"")) {
                    webSocket.send("""{"setupComplete":{}}""")
                }
                if (text.contains("audioStreamEnd")) {
                    // A phrase spoken right before stop still arrives after the
                    // finalize, which is why the client keeps reading.
                    webSocket.send("""{"serverContent":{"inputTranscription":{"text":"last words"}}}""")
                    webSocket.send("""{"serverContent":{"turnComplete":true}}""")
                }
            }
        })

        startClient()
        awaitUntil { events.contains("ready") }
        client.finishStreaming()
        awaitUntil { transcripts.isNotEmpty() }

        assertTrue(serverReceived.any { it.contains("audioStreamEnd") })
        assertEquals("last words", transcripts.single().text)
    }

    // ── helpers ────────────────────────────────────────────────────────

    private var currentServerSocket: WebSocket? = null

    private fun enqueueServer(listener: WebSocketListener) {
        val tracking = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                currentServerSocket = webSocket
                listener.onOpen(webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(webSocket, text)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(tracking))
    }

    private fun startClient() {
        client.startStreaming(
            apiKey = "test-key",
            sessionConfig = GeminiTranscriptionClient.buildSessionConfig(
                languageTag = "en_US",
                autoDetectLanguage = false,
                transcriptionMode = "SMART",
                endOfSpeechSilenceMs = 1500
            ),
            callback = object : GeminiTranscriptionClient.StreamingCallback {
                override fun onStreamReady() {
                    events.add("ready")
                }

                override fun onTranscriptionResult(segment: TranscriptSegment) {
                    transcripts.add(segment)
                }

                override fun onServerResponse(hasTranscriptText: Boolean) {
                    events.add("response")
                }

                override fun onSessionExpiring(timeLeftMs: Long) {
                    events.add("expiring:$timeLeftMs")
                }

                override fun onStreamError(error: String) {
                    events.add("error:$error")
                }

                override fun onStreamClosed() {
                    events.add("closed")
                }
            }
        )
    }

    /**
     * Pump the paused Robolectric main looper until [condition] holds. The
     * client's callbacks are posted to the main thread from OkHttp's reader
     * thread, so both sides have to be given a chance to run.
     */
    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        fail("condition not met within ${timeoutMs}ms; events=$events, serverReceived=$serverReceived")
    }
}
