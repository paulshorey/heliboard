// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Looper
import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Real frames and asynchronous callbacks against the documented MAI protocol. */
@RunWith(RobolectricTestRunner::class)
class MaiTranscriptionClientStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaiTranscriptionClient
    private val frames = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()
    private val transcripts = CopyOnWriteArrayList<TranscriptSegment>()
    @Volatile private var serverSocket: WebSocket? = null

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = MaiTranscriptionClient(
            httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
            endpointOverride = server.url("/mai/v1/realtime?intent=transcription").toString().replaceFirst("http", "ws"),
            completionTimeoutMs = 1000,
        )
    }

    @After fun tearDown() {
        client.cancelAll()
        server.shutdown()
    }

    @Test fun authenticatesByHeaderAndWaitsForSessionUpdatedBeforeAudio() {
        enqueueSocket(autoReady = false)
        start()
        awaitUntil { frames.isNotEmpty() }
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("api-key"))
        assertEquals("/mai/v1/realtime?intent=transcription", request.path)
        assertFalse(request.path!!.contains("test-key"))
        assertFalse(client.sendAudioChunk(byteArrayOf(1, 0)))
        assertEquals("session.update", JSONObject(frames.single()).getString("type"))
        send("""{"type":"session.updated"}""")
        awaitUntil { "ready" in events }
        assertTrue(client.sendAudioChunk(byteArrayOf(1, 0, -1, 127)))
        awaitUntil { audioFrames().isNotEmpty() }
        assertEquals(listOf<Byte>(1, 0, -1, 127), audioFrames().single().getString("audio").decodeBase64()!!.toByteArray().toList())
    }

    @Test fun provisionalAndDeltaEventsNeverReachTheEditor() {
        ready()
        assertTrue(client.sendAudioChunk(byteArrayOf(1, 0)))
        send("""{"type":"conversation.item.input_audio_transcription.delta","delta":"Hello"}""")
        send("""{"type":"conversation.item.input_audio_transcription.intermediate","intermediate":" world"}""")
        send("""{"type":"conversation.item.input_audio_transcription.intermediate","intermediate":" there"}""")
        assertTrue(client.finalizeTurn())
        complete("Hello there!")
        awaitUntil { transcripts.size == 1 }
        assertEquals("Hello there!", transcripts.single().text)
    }

    @Test fun stopWaitsForCompletedAfterTheCommitAcknowledgement() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        client.finishStreaming()
        awaitUntil { commitFrames().size == 1 }
        send("""{"type":"input_audio_buffer.committed","item_id":"one"}""")
        barrier()
        assertTrue(client.hasPendingProcessing)
        assertFalse("closed" in events)
        assertFalse(client.sendAudioChunk(byteArrayOf(1, 0)))
        complete("The final phrase.", "one")
        awaitUntil { "closed" in events }
        assertEquals("The final phrase.", transcripts.single().text)
        assertTrue(events.indexOf("text") < events.indexOf("closed"))
        assertFalse(client.hasPendingProcessing)
    }

    @Test fun pauseCommitsAndTheSameSocketAcceptsMoreSpeech() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        assertTrue(client.finalizeTurn())
        complete("First phrase.", "one")
        awaitUntil { transcripts.size == 1 }
        assertFalse("closed" in events)
        assertTrue(client.sendAudioChunk(byteArrayOf(2, 0)))
        client.finishStreaming()
        complete("Second phrase.", "two")
        awaitUntil { "closed" in events }
        assertEquals(listOf("First phrase.", "Second phrase."), transcripts.map { it.text })
    }

    @Test fun deliversOutOfOrderCompletionsInCommitOrderAndDeduplicatesItemIds() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        client.finalizeTurn()
        client.sendAudioChunk(byteArrayOf(2, 0))
        client.finalizeTurn()
        send("""{"type":"input_audio_buffer.committed","item_id":"one"}""")
        send("""{"type":"input_audio_buffer.committed","item_id":"two"}""")
        complete("Second.", "two")
        barrier()
        assertTrue(transcripts.isEmpty())
        complete("First.", "one")
        complete("First.", "one")
        complete("Second.", "two")
        barrier()
        assertEquals(listOf("First.", "Second."), transcripts.map { it.text })
        assertFalse(client.hasPendingProcessing)
    }

    @Test fun repeatedPhrasesInDifferentCommitsRemainRepeated() {
        ready()
        repeat(2) {
            client.sendAudioChunk(byteArrayOf(1, 0))
            client.finalizeTurn()
            complete("Yes.") // Microsoft's minimal completion example omits item_id.
            awaitUntil { transcripts.size == it + 1 }
        }
        assertEquals(listOf("Yes.", "Yes."), transcripts.map { it.text })
    }

    @Test fun stopDrainsEveryOutstandingCommitIncludingEarlierPausedSpeech() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        client.finalizeTurn()
        client.sendAudioChunk(byteArrayOf(2, 0))
        client.finishStreaming()
        awaitUntil { commitFrames().size == 2 }
        complete("First.")
        barrier()
        assertFalse("closed" in events)
        complete("Last.")
        awaitUntil { "closed" in events }
        assertEquals(listOf("First.", "Last."), transcripts.map { it.text })
    }

    @Test fun aTimeoutReportsIncompleteDictationAndDoesNotPromoteAnIntermediate() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        send("""{"type":"conversation.item.input_audio_transcription.intermediate","intermediate":"unfinished"}""")
        client.finishStreaming()
        awaitUntil { commitFrames().isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(events.any { it.startsWith("error:false:") && it.contains("could not be finalized") })
        assertTrue(transcripts.isEmpty())
        assertFalse(client.hasPendingProcessing)
    }

    @Test fun brokenConnectionWithUploadedAudioCannotSilentlyRetry() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        serverSocket!!.close(1011, "server error")
        awaitUntil { events.any { it.startsWith("error:") } }
        assertTrue(events.single { it.startsWith("error:") }.startsWith("error:false:"))
    }

    @Test fun authRejectionFailsWithoutRetryOrCredentialDisclosure() {
        server.enqueue(MockResponse().setResponseCode(401))
        start()
        awaitUntil { events.any { it.startsWith("error:") } }
        assertTrue(events.single { it.startsWith("error:") }.startsWith("error:false:"))
        assertFalse(events.joinToString().contains("test-key"))
    }

    @Test fun cancelsSuppressAllQueuedResultsAndCanStartAFreshSession() {
        ready()
        client.sendAudioChunk(byteArrayOf(1, 0))
        client.finalizeTurn()
        complete("Discard me.")
        client.cancelAll() // Do not pump the main looper until after cancellation.
        ready()
        assertTrue(transcripts.isEmpty())
        client.sendAudioChunk(byteArrayOf(2, 0))
        client.finishStreaming()
        complete("Keep me.")
        awaitUntil { "closed" in events }
        assertEquals("Keep me.", transcripts.single().text)
    }

    @Test fun emptyFinalsDrainAndStandalonePunctuationAttaches() {
        ready()
        assertFalse(client.finalizeTurn())
        client.sendAudioChunk(byteArrayOf(1, 0))
        client.finalizeTurn()
        complete(" ")
        barrier()
        assertTrue(transcripts.isEmpty())
        client.sendAudioChunk(byteArrayOf(1, 0))
        client.finishStreaming()
        complete("!")
        awaitUntil { "closed" in events }
        assertTrue(transcripts.single().attachesToPrevious)
    }

    @Test fun invalidPcmFailsBeforeItCanReachTheService() {
        ready()
        assertFalse(client.sendAudioChunk(byteArrayOf(1)))
        assertTrue(events.any { it.startsWith("error:false:") })
        assertTrue(audioFrames().isEmpty())
    }

    @Test fun rawServiceErrorMessagesAreNeverExposed() {
        ready()
        send("""{"type":"error","error":{"code":"bad_request","message":"test-key private dictated text"}}""")
        awaitUntil { events.any { it.startsWith("error:") } }
        assertFalse(events.joinToString().contains("test-key"))
        assertFalse(events.joinToString().contains("private dictated text"))
    }

    private fun start() {
        client.startStreaming(MaiConfig("test-key", "https://resource.services.ai.azure.com", "dictation", false), "en-US",
            object : MaiTranscriptionClient.StreamingCallback {
                override fun onStreamReady() { events.add("ready") }
                override fun onTranscriptionResult(segment: TranscriptSegment) { transcripts.add(segment); events.add("text") }
                override fun onPendingProcessingChanged() { events.add("pending") }
                override fun onStreamError(error: String, retryable: Boolean) { events.add("error:$retryable:$error") }
                override fun onStreamClosed() { events.add("closed") }
            })
    }
    private fun ready() {
        events.clear()
        enqueueSocket()
        start()
        awaitUntil { "ready" in events }
    }
    private fun enqueueSocket(autoReady: Boolean = true) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.add(text)
                if (autoReady && JSONObject(text).optString("type") == "session.update")
                    webSocket.send("""{"type":"session.updated"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
    }
    private fun send(event: String) { assertTrue(serverSocket!!.send(event)) }
    private fun complete(text: String, id: String? = null) {
        val event = JSONObject().put("type", "conversation.item.input_audio_transcription.completed").put("transcript", text)
        if (id != null) event.put("item_id", id)
        send(event.toString())
    }
    private fun audioFrames() = frames.map { JSONObject(it) }.filter { it.optString("type") == "input_audio_buffer.append" }
    private fun commitFrames() = frames.map { JSONObject(it) }.filter { it.optString("type") == "input_audio_buffer.commit" }
    private fun barrier() {
        // Use a subsequent protocol event as a FIFO barrier on the reader and main threads.
        val before = helium314.keyboard.latin.utils.Log.getLog().size
        send("""{"type":"test.barrier"}""")
        awaitUntil { helium314.keyboard.latin.utils.Log.getLog().drop(before).any { it.message.contains("test.barrier") } }
    }
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out: events=$events frames=$frames")
    }
}
