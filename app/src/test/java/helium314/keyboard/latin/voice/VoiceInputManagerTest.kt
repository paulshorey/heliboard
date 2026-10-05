// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.prefs
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
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Exercise recorder callbacks against a real local MAI socket, without a microphone. */
@RunWith(RobolectricTestRunner::class)
class VoiceInputManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var manager: VoiceInputManager
    private lateinit var recording: VoiceRecorder.RecordingCallback
    private val frames = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()
    @Volatile private var socket: WebSocket? = null

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.prefs()
        prefs.edit().clear().commit()
        TranscriptionPreferences.writeMaiApiKey(prefs, "test-key")
        TranscriptionPreferences.writeMaiEndpoint(prefs, "https://resource.services.ai.azure.com")
        TranscriptionPreferences.writeMaiDeployment(prefs, "dictation")
        server = MockWebServer()
        server.start()
        val recorder = Mockito.mock(VoiceRecorder::class.java)
        Mockito.`when`(recorder.hasRecordPermission()).thenReturn(true)
        Mockito.`when`(recorder.startRecording()).thenReturn(true)
        Mockito.doAnswer { recording = it.getArgument(0); null }
            .`when`(recorder).setCallback(Mockito.any(VoiceRecorder.RecordingCallback::class.java))
        val client = MaiTranscriptionClient(
            OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
            server.url("/mai/v1/realtime").toString().replaceFirst("http", "ws"),
        )
        manager = VoiceInputManager(context, recorder, client)
        manager.setListener(object : VoiceInputManager.VoiceInputListener {
            override fun onStateChanged(state: VoiceInputManager.State) { events.add("state:$state") }
            override fun onTranscriptionResult(text: String, attachesToPrevious: Boolean) { events.add("text:$text") }
            override fun onProcessingStarted() { events.add("processing") }
            override fun onProcessingIdle() { events.add("idle") }
            override fun onPendingProcessingCancelled() { events.add("cancelled") }
            override fun onError(error: String) { events.add("error:$error") }
            override fun onPermissionRequired() { events.add("permission") }
        })
    }

    @After fun tearDown() { manager.destroy(); server.shutdown() }

    @Test fun stopBeforeHandshakeUploadsQueuedTailAndRemainsPendingUntilFinal() {
        enqueueSocket(autoReady = false)
        assertTrue(manager.startRecording())
        recording.onAudioChunk(byteArrayOf(1, 0))
        manager.stopRecording()
        awaitUntil { frames.any { type(it) == "session.update" } }
        assertTrue(manager.hasPendingProcessing())
        assertFalse(frames.any { type(it) == "input_audio_buffer.append" })
        socket!!.send("""{"type":"session.updated"}""")
        awaitUntil { commits() == 1 }
        assertTrue(manager.hasPendingProcessing())
        complete("Last word.")
        awaitUntil { manager.isIdle && !manager.hasPendingProcessing() }
        assertTrue("text:Last word." in events)
        assertTrue(events.indexOf("text:Last word.") < events.lastIndexOf("idle"))
    }

    @Test fun silenceBeforeHandshakeStillRequestsAFinalWhenReady() {
        enqueueSocket(autoReady = false)
        manager.startRecording()
        recording.onAudioChunk(byteArrayOf(1, 0))
        recording.onSpeechStopped()
        awaitUntil { frames.any { type(it) == "session.update" } }
        socket!!.send("""{"type":"session.updated"}""")
        awaitUntil { commits() == 1 }
        complete("Complete sentence.")
        awaitUntil { "text:Complete sentence." in events }
        assertTrue(manager.isRecording)
    }

    @Test fun pauseDuringHandshakeThenResumeDoesNotCommitActiveSpeech() {
        enqueueSocket(autoReady = false)
        manager.startRecording()
        recording.onAudioChunk(byteArrayOf(1, 0))
        manager.pauseRecording()
        manager.resumeRecording()
        awaitUntil { frames.any { type(it) == "session.update" } }
        socket!!.send("""{"type":"session.updated"}""")
        awaitUntil { frames.any { type(it) == "input_audio_buffer.append" } }
        assertEquals(0, commits())
        manager.stopRecording()
        awaitUntil { commits() == 1 }
        complete("Continued speech.")
        awaitUntil { manager.isIdle }
        assertTrue("text:Continued speech." in events)
    }

    @Test fun stopDuringStartupBackoffRetriesAndDrainsBufferedSpeech() {
        server.enqueue(MockResponse().setResponseCode(503))
        enqueueSocket()
        manager.startRecording()
        recording.onAudioChunk(byteArrayOf(1, 0))
        awaitUntil { helium314.keyboard.latin.utils.Log.getLog().any { it.message.contains("Retrying MAI connection") } }
        manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        awaitUntil { commits() == 1 }
        complete("Buffered phrase.")
        awaitUntil { manager.isIdle && !manager.hasPendingProcessing() }
        assertTrue("text:Buffered phrase." in events)
        assertFalse(events.any { it.startsWith("error:") })
    }

    @Test fun rotationDrainsOutgoingSpeechBeforeUploadingBufferedSpeechToReplacement() {
        enqueueSocket()
        enqueueSocket()
        manager.startRecording()
        awaitUntil { frames.any { type(it) == "session.update" } && !manager.hasPendingProcessing() }
        recording.onSpeechStarted() // cancel initial auto-stop; simulate uninterrupted speech
        recording.onAudioChunk(byteArrayOf(1, 0))
        awaitUntil { frames.any { type(it) == "input_audio_buffer.append" } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MaiTranscriptionClient.SESSION_ROTATE_AFTER_MS))
        awaitUntil { commits() == 1 }
        recording.onAudioChunk(byteArrayOf(2, 0))
        assertEquals(1, frames.count { type(it) == "input_audio_buffer.append" })
        complete("Outgoing phrase.")
        awaitUntil { frames.count { type(it) == "input_audio_buffer.append" } == 2 }
        manager.stopRecording()
        awaitUntil { commits() == 2 }
        complete("Replacement phrase.")
        awaitUntil { manager.isIdle && !manager.hasPendingProcessing() }
        assertEquals(listOf("text:Outgoing phrase.", "text:Replacement phrase."), events.filter { it.startsWith("text:") })
    }

    @Test fun stopAfterSilenceKeepsTheHeldOnsetEvenBeforeVadDetectsNewSpeech() {
        enqueueSocket()
        manager.startRecording()
        awaitUntil { frames.any { type(it) == "session.update" } && !manager.hasPendingProcessing() }
        recording.onAudioChunk(byteArrayOf(1, 0))
        recording.onSpeechStopped()
        awaitUntil { commits() == 1 }
        complete("First phrase.")
        awaitUntil { "text:First phrase." in events }
        manager.stopRecording()
        // This queued read can contain the onset of a short word. No speechStarted
        // callback has fired yet, so stop must drain the held prefix itself.
        recording.onAudioChunk(byteArrayOf(2, 0))
        awaitUntil { commits() == 2 }
        assertEquals(2, frames.count { type(it) == "input_audio_buffer.append" })
        complete("Last word.")
        awaitUntil { manager.isIdle && !manager.hasPendingProcessing() }
        assertTrue("text:Last word." in events)
    }

    @Test fun cancelDuringFinalizationSuppressesLateTextAndClearsPendingWork() {
        enqueueSocket()
        manager.startRecording()
        awaitUntil { frames.any { type(it) == "session.update" } && !manager.hasPendingProcessing() }
        recording.onAudioChunk(byteArrayOf(1, 0))
        manager.stopRecording()
        awaitUntil { commits() == 1 }
        complete("Discarded phrase.")
        manager.cancelRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.isIdle)
        assertFalse(manager.hasPendingProcessing())
        assertFalse(events.any { it.startsWith("text:") })
        assertTrue("cancelled" in events)
    }

    private fun enqueueSocket(autoReady: Boolean = true) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                socket = webSocket
                webSocket.send("""{"type":"session.created"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.add(text)
                if (autoReady && type(text) == "session.update") webSocket.send("""{"type":"session.updated"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
    }
    private fun complete(text: String) {
        socket!!.send(JSONObject().put("type", "conversation.item.input_audio_transcription.completed").put("transcript", text).toString())
    }
    private fun type(frame: String) = JSONObject(frame).optString("type")
    private fun commits() = frames.count { type(it) == "input_audio_buffer.commit" }
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out: events=$events frames=$frames")
    }
}
