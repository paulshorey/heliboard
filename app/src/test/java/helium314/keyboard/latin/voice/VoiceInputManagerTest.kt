// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.prefs
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VoiceInputManagerTest {
    private lateinit var manager: VoiceInputManager
    private lateinit var capture: VoiceRecorder.RecordingCallback
    private lateinit var stream: GeminiTranscriptionClient.StreamingCallback
    private var network = FakeNetwork()
    private val sent = mutableListOf<String>()
    private val inserted = mutableListOf<String>()
    private val markers = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var speaking = false
    private var sendResult = GeminiTranscriptionClient.AudioSendResult.ACCEPTED
    private var transportQueued = false
    private var editorAccepts = true
    private var editorThrows = false
    private var finalizeAccepts = true
    private var processing = false
    private var starts = 0
    private var stopped = 0
    private var onInsert: (() -> Unit)? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.prefs().edit().clear().commit()
        TranscriptionPreferences.writeGeminiApiKey(context.prefs(), "test-key")
        val recorder = Mockito.mock(VoiceRecorder::class.java) { call ->
            when (call.method.name.substringBefore('$')) {
                "hasRecordPermission", "startRecording" -> true
                "isCurrentlySpeaking" -> speaking
                "setCallback" -> { capture = call.arguments[0] as VoiceRecorder.RecordingCallback; null }
                "stopRecording" -> { stopped++; null }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        val client = Mockito.mock(GeminiTranscriptionClient::class.java) { call ->
            when (call.method.name.substringBefore('$')) {
                "startStreaming" -> { starts++; stream = call.arguments[2] as GeminiTranscriptionClient.StreamingCallback; null }
                "offerAudioChunk" -> {
                    if (sendResult == GeminiTranscriptionClient.AudioSendResult.ACCEPTED) {
                        sent.add("audio:${(call.arguments[0] as ByteArray)[0]}")
                    }
                    sendResult
                }
                "finalizeTurn" -> { sent.add("end"); finalizeAccepts }
                "finishStreaming" -> { sent.add("finish"); finalizeAccepts }
                "hasQueuedFrames" -> transportQueued
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        manager = VoiceInputManager(context, recorder, client, network)
        manager.setListener(object : VoiceInputManager.VoiceInputListener {
            override fun onStateChanged(state: VoiceInputManager.State) { }
            override fun onTranscriptionResult(text: String, attachesToPrevious: Boolean): Boolean {
                if (editorThrows) error("editor disconnected")
                onInsert?.invoke()
                if (editorAccepts) inserted.add(text)
                return editorAccepts
            }
            override fun onTranscriptionInterrupted(marker: String): Boolean { markers.add(marker); return true }
            override fun onProcessingStarted() { processing = true }
            override fun onProcessingIdle() { processing = false }
            override fun onPendingProcessingCancelled() { }
            override fun onError(error: String) { errors.add(error) }
            override fun onPermissionRequired() { error("unexpected permission request") }
        })
    }

    @After fun tearDown() { manager.destroy() }
    private fun start(ready: Boolean = true) {
        assertTrue(manager.startRecording())
        if (ready) stream.onStreamReady()
    }
    private fun audio(value: Int = 1, size: Int = 3200) = capture.onAudioChunk(ByteArray(size) { value.toByte() })
    private fun final(text: String) {
        stream.onTranscriptionResult(TranscriptSegment(text, false))
        stream.onServerResponse(true)
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun offlinePreflightDoesNotStartCaptureOrWriteAMarker() {
        network.available = false
        assertFalse(manager.startRecording())
        assertTrue(manager.isIdle)
        assertEquals(0, starts)
        assertTrue(markers.isEmpty())
    }

    @Test fun lossPreservesAcceptedPrefixAndRejectsEveryLateCallback() {
        start(); speaking = true; audio(); final("confirmed prefix")
        audio(2)
        val oldCapture = capture
        val oldStream = stream
        val oldNetwork = network.loss!!
        oldNetwork()
        assertTrue(manager.isIdle)
        assertEquals(listOf("confirmed prefix"), inserted)
        assertEquals(listOf(VoiceInputManager.INTERRUPTION_MARKER), markers)
        assertTrue(stopped > 0)
        oldStream.onStreamReady()
        oldStream.onTranscriptionResult(TranscriptSegment("automatic suffix", false))
        oldCapture.onAudioChunk(ByteArray(3200))
        oldNetwork()
        advance(40_000)
        assertEquals(1, starts)
        assertEquals(1, markers.size)
        assertEquals(listOf("confirmed prefix"), inserted)
        assertFalse(processing)
    }

    @Test fun explicitNewRecordingRejectsOldNetworkAndStreamEvents() {
        start(); audio(); val oldStream = stream; val oldLoss = network.loss!!
        oldLoss()
        start()
        oldLoss(); oldStream.onStreamClosed(); oldStream.onStreamError("old failure")
        oldStream.onTranscriptionResult(TranscriptSegment("stale", false))
        assertTrue(manager.isRecording)
        final("new recording")
        assertEquals(listOf("new recording"), inserted)
        assertEquals(2, starts)
        assertEquals(1, markers.size)
    }

    @Test fun backpressureRetainsEveryAudioChunkAndStopCannotOvertakeIt() {
        start(); sendResult = GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE
        repeat(300) { audio(it % 100) }
        manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sent.isEmpty())
        assertFalse(manager.startRecording())
        assertTrue(manager.hasPendingProcessing())
        sendResult = GeminiTranscriptionClient.AudioSendResult.ACCEPTED
        advance(30)
        assertEquals((0 until 300).map { "audio:${it % 100}" } + "finish", sent)
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertTrue(markers.isEmpty())
    }

    @Test fun silencePauseAndStopControlsKeepTheirPositionInTheAudioQueue() {
        start(); sendResult = GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(1); capture.onSpeechStopped(); audio(2)
        manager.pauseRecording(); shadowOf(Looper.getMainLooper()).idle()
        manager.resumeRecording(); audio(3); manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()
        sendResult = GeminiTranscriptionClient.AudioSendResult.ACCEPTED
        advance(30)
        assertEquals(listOf("audio:1", "end", "audio:2", "end", "audio:3", "finish"), sent)
    }

    @Test fun overflowStopsInsteadOfDroppingTheOldestAudio() {
        start(); sendResult = GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE
        repeat(301) { audio() }
        assertTrue(manager.isIdle)
        sendResult = GeminiTranscriptionClient.AudioSendResult.ACCEPTED
        advance(40_000)
        assertTrue(sent.isEmpty())
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("queue is full"))
    }

    @Test fun oldestUploadDeadlineCannotBePostponedByMoreAudio() {
        start(); sendResult = GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(); advance(15_000); audio(); advance(15_100)
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("upload stalled"))
    }

    @Test fun continuousPcmCannotPostponeTheResponseDeadline() {
        start(); speaking = true; audio()
        repeat(29) { advance(1000); audio() }
        advance(1100)
        assertTrue(manager.isIdle)
        assertTrue(errors.single().contains("stopped responding"))
        assertEquals(1, markers.size)
    }

    @Test fun interimUpdatesDoNotAcknowledgePendingSpeech() {
        start(); speaking = true; audio()
        repeat(29) {
            advance(1000); audio(); stream.onInterimTranscription(); stream.onServerResponse(false)
        }
        advance(1100)
        assertTrue(manager.isIdle)
        assertTrue(errors.single().contains("No final transcript"))
        assertTrue(inserted.isEmpty())
        assertEquals(1, markers.size)
    }

    @Test fun turnCompleteCannotHideAnUnconfirmedTailOnStop() {
        start(); audio(); stream.onInterimTranscription(); stream.onServerResponse(false)
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        stream.onServerResponse(false); stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
    }

    @Test fun earlierFinalCannotAcknowledgeAudioStillWaitingInTheManager() {
        start(); speaking = true; audio(1)
        sendResult = GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(2); final("confirmed prefix")
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        sendResult = GeminiTranscriptionClient.AudioSendResult.ACCEPTED
        advance(30); stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(listOf("confirmed prefix"), inserted)
        assertEquals(listOf("audio:1", "audio:2", "finish"), sent)
        assertEquals(1, markers.size)
    }

    @Test fun earlierFinalCannotAcknowledgeAudioStillWaitingInTheSocket() {
        start(); speaking = true; audio()
        transportQueued = true; final("confirmed prefix")
        transportQueued = false
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(listOf("confirmed prefix"), inserted)
        assertEquals(1, markers.size)
    }

    @Test fun queuedAudioKeepsTheOriginalFinalProgressDeadlineAfterAnEarlierFinal() {
        start(); speaking = true; audio(); advance(29_000)
        transportQueued = true; final("confirmed prefix")
        advance(1100)
        assertTrue(manager.isIdle)
        assertTrue(errors.single().contains("No final transcript"))
        assertEquals(listOf("confirmed prefix"), inserted)
        assertEquals(1, markers.size)
    }

    @Test fun laterFinalAfterLocalQueuesDrainCanCompleteTheSession() {
        start(); speaking = true; audio()
        transportQueued = true; final("confirmed prefix")
        transportQueued = false
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        final("confirmed suffix"); stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(listOf("confirmed prefix", "confirmed suffix"), inserted)
        assertTrue(markers.isEmpty())
    }

    @Test fun stopWaitsForTheFinalAndBlocksRestartUntilTheSocketCloses() {
        start(); speaking = true; audio(); manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(manager.startRecording())
        final("the final words")
        assertTrue(manager.hasPendingProcessing())
        assertTrue(processing)
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertFalse(processing)
        assertTrue(markers.isEmpty())
        assertEquals(listOf("the final words"), inserted)
    }

    @Test fun editorRejectionPreventsEveryLaterInsertionAndDoesNotRetryAMarker() {
        start(); audio(); editorAccepts = false; final("rejected")
        final("later")
        assertTrue(manager.isIdle)
        assertTrue(inserted.isEmpty())
        assertTrue(markers.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun editorExceptionIsTerminal() {
        start(); audio(); editorThrows = true; final("rejected"); final("later")
        assertTrue(manager.isIdle)
        assertTrue(inserted.isEmpty())
        assertTrue(markers.isEmpty())
        assertEquals(1, errors.size)
    }

    @Test fun transcriptQueueIsConsumedInOrderOnlyAfterAcknowledgment() {
        start()
        onInsert = { onInsert = null; stream.onTranscriptionResult(TranscriptSegment("second", false)) }
        final("first")
        assertEquals(listOf("first", "second"), inserted)
    }

    @Test fun transcriptQueueOverflowIsTerminalWithoutCoalescingAcrossAGap() {
        start(); audio()
        onInsert = {
            onInsert = null
            repeat(65) { stream.onTranscriptionResult(TranscriptSegment("queued $it", false)) }
        }
        final("head")
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("text queue is full"))
    }

    @Test fun failedSendAndFailedFinalizeDoNotReconnect() {
        start(); sendResult = GeminiTranscriptionClient.AudioSendResult.FAILED; audio()
        advance(40_000)
        assertTrue(manager.isIdle)
        assertEquals(1, starts)
        assertEquals(1, markers.size)
    }

    @Test fun failedPauseFinalizationStopsTheSession() {
        start(); audio(); finalizeAccepts = false; manager.pauseRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
    }

    @Test fun plannedSessionEndDrainsWithoutCreatingAReplacementConnection() {
        start(); speaking = true; audio()
        stream.onSessionExpiring(30_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("finish", sent.last())
        final("last words"); stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(1, starts)
        assertTrue(markers.isEmpty())
    }

    @Test fun microphoneErrorIsTerminalAndLateAudioIsIgnored() {
        start(); audio(); capture.onRecordingError("mic failure")
        val count = sent.size
        audio(2); final("late result")
        assertTrue(manager.isIdle)
        assertEquals(count, sent.size)
        assertTrue(inserted.isEmpty())
        assertEquals(1, markers.size)
    }

    @Test fun setupFallbackKeepsTheOriginalConnectionDeadline() {
        start(ready = false); audio(); advance(11_000)
        stream.onHandshakeRestarted(); advance(1100)
        assertTrue(manager.isIdle)
        assertTrue(errors.single().contains("connection timed out"))
        assertEquals(1, markers.size)
    }

    @Test fun quietPcmIsNotDiscardedWhileWaitingForSpeech() {
        start(); capture.onSpeechStopped(); audio(0); audio(1)
        assertEquals(listOf("end", "audio:0", "audio:1"), sent)
    }

    @Test fun cancelDiscardsPendingWorkWithoutAnInterruptionMarker() {
        start(); speaking = true; audio(); manager.cancelRecording(); final("late")
        assertTrue(manager.isIdle)
        assertTrue(markers.isEmpty())
        assertTrue(inserted.isEmpty())
    }

    @Test fun stopBeforeSetupRetainsTheFinalCapturedAudioBeforeEof() {
        start(ready = false); audio(1); manager.stopRecording()
        // Simulate the recorder tail already posted ahead of the stop barrier.
        audio(2); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sent.isEmpty())
        stream.onStreamReady()
        assertEquals(listOf("audio:1", "audio:2", "finish"), sent)
    }

    @Test fun aPausedConnectionLossIsTerminalRatherThanResumingOnANewSocket() {
        start(); audio(); manager.pauseRecording(); shadowOf(Looper.getMainLooper()).idle()
        stream.onStreamError("network failure")
        manager.resumeRecording()
        assertTrue(manager.isIdle)
        assertEquals(1, starts)
        assertEquals(1, markers.size)
    }

    @Test fun stopDeadlineStillAppliesAfterTheFinalArrives() {
        start(); speaking = true; audio(); manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idle(); final("confirmed")
        advance(VoiceInputManager.DRAIN_TIMEOUT_MS + 1)
        assertTrue(manager.isIdle)
        assertEquals(listOf("confirmed"), inserted)
        assertTrue(errors.single().contains("finish closing"))
    }

    @Test fun silentCaptureDoesNotStartATranscriptProgressDeadline() {
        start(); final("confirmed"); audio(0)
        advance(29_000)
        assertTrue(manager.isRecording)
        assertTrue(errors.isEmpty())
    }

    @Test fun speechOnsetPostedBeforeStopIsRetainedThroughTheTailBarrier() {
        start(); manager.stopRecording()
        audio(); capture.onSpeechStarted()
        shadowOf(Looper.getMainLooper()).idle()
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
    }

    private class FakeNetwork : VoiceNetworkMonitor {
        var available = true
        var loss: (() -> Unit)? = null
        override fun start(onUnavailable: () -> Unit): Boolean { loss = onUnavailable; return available }
        override fun stop() { }
    }
}
