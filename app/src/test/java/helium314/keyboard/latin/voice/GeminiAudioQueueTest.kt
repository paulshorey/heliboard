// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Looper
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Exercises the production bounded sender against a controllable socket queue. */
@RunWith(RobolectricTestRunner::class)
class GeminiAudioQueueTest {
    private lateinit var client: GeminiTranscriptionClient
    private lateinit var transport: WebSocketListener
    private val socket = FakeSocket()
    private val errors = mutableListOf<String>()
    private val transcripts = mutableListOf<String>()
    private var closed = false

    @Before fun setUp() {
        client = GeminiTranscriptionClient()
        client.socketFactory = WebSocket.Factory { _, listener -> transport = listener; socket }
        client.startStreaming("test-key", GeminiTranscriptionClient.buildSessionConfig("en_US", false, "SMART", 1500),
            object : GeminiTranscriptionClient.StreamingCallback {
                override fun onStreamReady() { }
                override fun onTranscriptionResult(segment: TranscriptSegment) { transcripts.add(segment.text) }
                override fun onInterimTranscription() { }
                override fun onServerResponse(hasTranscriptText: Boolean) { }
                override fun onSessionExpiring(timeLeftMs: Long) { }
                override fun onStreamError(error: String) { errors.add(error) }
                override fun onStreamClosed() { closed = true }
            })
        transport.onMessage(socket, """{"setupComplete":{}}""")
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun tearDown() { client.cancelAll() }

    @Test fun fullSocketQueueIsBackpressureRatherThanAWriteFailure() {
        socket.bytes = GeminiTranscriptionClient.MAX_SOCKET_AUDIO_BYTES
        assertEquals(GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE, client.offerAudioChunk(ByteArray(3200)))
        assertTrue(socket.messages.isEmpty())
        socket.bytes = 0
        assertEquals(GeminiTranscriptionClient.AudioSendResult.ACCEPTED, client.offerAudioChunk(ByteArray(3200)))
        assertEquals(1, socket.messages.size)
    }

    @Test fun sendFalseIsTerminalRatherThanBackpressure() {
        socket.accepts = false
        assertEquals(GeminiTranscriptionClient.AudioSendResult.FAILED, client.offerAudioChunk(ByteArray(3200)))
        assertFalse(client.finalizeTurn())
        assertFalse(client.finishStreaming())
    }

    @Test fun invalidPcmIsRejectedWithoutEnqueuingAFrame() {
        for (pcm in listOf(ByteArray(0), ByteArray(3), ByteArray(300_000))) {
            assertEquals(GeminiTranscriptionClient.AudioSendResult.FAILED, client.offerAudioChunk(pcm))
        }
        assertTrue(socket.messages.isEmpty())
    }

    @Test fun failureDuringDrainInvalidatesQueuedAndLaterFrames() {
        assertTrue(client.finishStreaming())
        transport.onFailure(socket, IOException("connection lost"), null)
        transport.onMessage(socket, """{"serverContent":{"inputTranscription":{"text":"late suffix"}}}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, errors.size)
        assertTrue(transcripts.isEmpty())
        assertFalse(closed)
    }

    @Test fun stopDeadlineCannotTreatQueuedAudioAsDelivered() {
        assertTrue(client.sendAudioChunk(ByteArray(3200)))
        assertTrue(client.finishStreaming())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GeminiTranscriptionClient.FINALIZE_CLOSE_GRACE_MS))
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("did not drain"))
        assertTrue(socket.cancelled)
        assertFalse(closed)
    }

    private class FakeSocket : WebSocket {
        var bytes = 0L
        var accepts = true
        var cancelled = false
        val messages = mutableListOf<String>()
        override fun request(): Request = Request.Builder().url("https://example.test/").build()
        override fun queueSize() = bytes
        override fun send(text: String): Boolean {
            if (!accepts) return false
            messages.add(text); bytes += text.length
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = accepts
        override fun cancel() { cancelled = true }
    }
}
