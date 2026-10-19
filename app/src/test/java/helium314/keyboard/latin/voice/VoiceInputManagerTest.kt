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
    private lateinit var stream: MuseTranscriptionClient.StreamingCallback
    private var network = FakeNetwork()
    private val sent = mutableListOf<String>()
    private val inserted = mutableListOf<String>()
    private val markers = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var speaking = false
    private var sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
    private var transportQueued = false
    private var transportQueuedSilence = false
    private var editorAccepts = true
    private var editorThrows = false
    private var finalizeAccepts = true
    private var processing = false
    private var starts = 0
    private var stopped = 0
    private var onInsert: (() -> Unit)? = null
    private var onRecorderStop: (() -> Unit)? = null
    private var onStateChange: ((VoiceInputManager.State) -> Unit)? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.prefs().edit().clear().commit()
        TranscriptionPreferences.writeMuseApiKey(context, "test-key")
        val recorder = Mockito.mock(VoiceRecorder::class.java) { call ->
            when (call.method.name.substringBefore('$')) {
                "hasRecordPermission", "startRecording" -> true
                "getSampleRate" -> 24000
                "isCurrentlySpeaking" -> speaking
                "setCallback" -> { capture = call.arguments[0] as VoiceRecorder.RecordingCallback; null }
                "stopRecording" -> { stopped++; onRecorderStop?.invoke(); null }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        val client = Mockito.mock(MuseTranscriptionClient::class.java) { call ->
            when (call.method.name.substringBefore('$')) {
                "startStreaming" -> { starts++; stream = call.arguments[2] as MuseTranscriptionClient.StreamingCallback; null }
                "offerAudioChunk" -> {
                    if (sendResult == MuseTranscriptionClient.AudioSendResult.ACCEPTED) {
                        sent.add("audio:${(call.arguments[0] as ByteArray)[0]}")
                    }
                    sendResult
                }
                "finishStreaming" -> { sent.add("finish"); finalizeAccepts }
                "hasQueuedFrames" -> transportQueued || transportQueuedSilence
                "hasQueuedSpeechFrames" -> transportQueued
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        manager = VoiceInputManager(context, recorder, client, network)
        manager.setListener(object : VoiceInputManager.VoiceInputListener {
            override fun onStateChanged(state: VoiceInputManager.State) { onStateChange?.invoke(state) }
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

    @Test fun localSilenceDoesNotCommitTurnsAndPauseSendsSilenceUntilResume() {
        start(); audio(7); capture.onSpeechStopped()
        assertEquals(listOf("audio:7"), sent)
        manager.pauseRecording(); advance(250)
        assertEquals(listOf("audio:7", "audio:0", "audio:0", "audio:0"), sent)
        assertTrue(manager.isPaused)
        manager.resumeRecording(); val before = sent.size; advance(250)
        assertEquals(before, sent.size)
        audio(9); assertEquals("audio:9", sent.last())
        assertTrue(errors.isEmpty())
    }

    @Test fun filteredSpeakerFinalSettlesPendingSpeechWithoutAnEditorWrite() {
        start(); speaking = true; audio(); capture.onSpeechStarted(); stream.onInterimTranscription()
        stream.onServerResponse(true) // provider completed a discarded nonselected turn
        speaking = false; capture.onSpeechStopped()
        manager.stopRecording(); advance(1); stream.onStreamClosed()
        assertTrue(manager.isIdle); assertTrue(markers.isEmpty()); assertTrue(inserted.isEmpty())
    }

    @Test fun pauseAndResumeKeepSyntheticSilenceBehindBlockedMicrophoneTail() {
        start(); sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(7); manager.pauseRecording(); advance(1); manager.resumeRecording(); audio(9)
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED; advance(25)
        assertEquals(listOf("audio:7", "audio:0", "audio:9"), sent)
    }

    @After fun tearDown() { manager.destroy() }
    private fun start(ready: Boolean = true) {
        assertTrue(manager.startRecording())
        if (ready) stream.onStreamReady()
    }
    private fun audio(value: Int = 1, size: Int = 4800, containsSpeech: Boolean = speaking) =
        capture.onAudioChunk(ByteArray(size) { value.toByte() }, containsSpeech)
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
        oldCapture.onAudioChunk(ByteArray(3200), true)
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
        start(); sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        repeat(300) { audio(it % 100) }
        manager.stopRecording()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sent.isEmpty())
        assertFalse(manager.startRecording())
        assertTrue(manager.hasPendingProcessing())
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
        advance(30)
        assertEquals((0 until 300).map { "audio:${it % 100}" } + "finish", sent)
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertTrue(markers.isEmpty())
    }

    @Test fun overflowStopsInsteadOfDroppingTheOldestAudio() {
        start(); sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        repeat(301) { audio() }
        assertTrue(manager.isIdle)
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
        advance(40_000)
        assertTrue(sent.isEmpty())
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("queue is full"))
    }

    @Test fun oldestUploadDeadlineCannotBePostponedByMoreAudio() {
        start(); sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(); advance(15_000); audio(); advance(15_100)
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("upload stalled"))
    }

    @Test fun continuousPcmCannotPostponeTheResponseDeadline() {
        start(); speaking = true; audio(); capture.onSpeechStarted()
        repeat(29) { advance(1000); audio() }
        advance(1100)
        assertTrue(manager.isIdle)
        assertTrue(errors.single().contains("stopped responding"))
        assertEquals(1, markers.size)
    }

    @Test fun aLongUtteranceWithLiveInterimsWaitsUntilSpeechEndsForAFinal() {
        start(); speaking = true; audio(); capture.onSpeechStarted()
        repeat(65) {
            advance(1000); audio(); stream.onInterimTranscription(); stream.onServerResponse(false)
        }
        assertTrue(manager.isRecording)
        assertTrue(inserted.isEmpty())
        speaking = false; capture.onSpeechStopped()
        advance(5000); final("All of the long utterance.")
        assertTrue(manager.isRecording)
        assertTrue(errors.isEmpty())
        assertTrue(markers.isEmpty())
        assertEquals(listOf("All of the long utterance."), inserted)
    }

    @Test fun aLongPauseAfterAConfirmedFinalDoesNotInventPendingSpeech() {
        start(); speaking = true; audio(); capture.onSpeechStarted(); final("First sentence.")
        speaking = false; audio(0); capture.onSpeechStopped()
        advance(20_000)
        speaking = true; audio(); capture.onSpeechStarted()
        repeat(20) {
            advance(1000); audio(); stream.onInterimTranscription(); stream.onServerResponse(false)
        }
        final("Second sentence.")
        assertTrue(manager.isRecording)
        assertTrue(errors.isEmpty())
        assertTrue(markers.isEmpty())
        assertEquals(listOf("First sentence.", "Second sentence."), inserted)
    }

    @Test fun earlierFinalCannotAcknowledgeAudioStillWaitingInTheManager() {
        start(); speaking = true; audio(1)
        sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(2); final("confirmed prefix")
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
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
        assertTrue(errors.single().contains("wait for processing"))
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
        start(); sendResult = MuseTranscriptionClient.AudioSendResult.FAILED; audio()
        advance(40_000)
        assertTrue(manager.isIdle)
        assertEquals(1, starts)
        assertEquals(1, markers.size)
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

    @Test fun startupRejectionStopsCaptureAndLeavesTheExistingEditorTextUntouched() {
        inserted.add("existing editor text")
        start(ready = false); audio()
        stream.onStreamError("Muse rejected billing")
        stream.onStreamReady(); audio(2); final("late words")
        assertTrue(manager.isIdle)
        assertTrue(stopped > 0)
        assertEquals(listOf("existing editor text"), inserted)
        assertTrue(markers.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(listOf("Could not start dictation. Muse rejected billing"), errors)
    }

    @Test fun quietPcmIsNotDiscardedWhileWaitingForSpeech() {
        start(); capture.onSpeechStopped(); audio(0); audio(1)
        assertEquals(listOf("audio:0", "audio:1"), sent)
    }

    @Test fun cancelDiscardsPendingWorkWithoutAnInterruptionMarker() {
        start(); speaking = true; audio(); manager.cancelRecording(); final("late")
        assertTrue(manager.isIdle)
        assertTrue(markers.isEmpty())
        assertTrue(inserted.isEmpty())
    }

    @Test fun cancellationDuringDrainClearsProcessingAndAllowsImmediateRestart() {
        start(); speaking = true; audio()
        val oldStream = stream
        val oldCapture = capture
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.hasPendingProcessing())
        assertTrue(processing)
        manager.cancelRecording()
        assertTrue(manager.isIdle)
        assertFalse(processing)
        start()
        oldStream.onStreamClosed()
        oldStream.onStreamError("old failure")
        oldStream.onTranscriptionResult(TranscriptSegment("old pending words", false))
        oldCapture.onAudioChunk(ByteArray(3200), true)
        final("New session words.")
        assertTrue(manager.isRecording)
        assertEquals(listOf("New session words."), inserted)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
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
        assertTrue(errors.single().contains("too long to finish"))
    }

    @Test fun blockedUploadFailsBeforeOverallStopDeadlineAndAllowsExplicitRestart() {
        ApplicationProvider.getApplicationContext<Context>().prefs().edit().putInt("voice_auto_stop_silence_seconds", 300).commit()
        start(); sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(containsSpeech = true)
        val oldStream = stream
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        advance(VoiceInputManager.PROGRESS_TIMEOUT_MS - 1)
        assertTrue(processing)
        assertTrue(manager.hasPendingProcessing())
        advance(30)
        assertTrue(manager.isIdle)
        assertFalse(processing)
        assertFalse(manager.hasPendingProcessing())
        assertTrue(sent.isEmpty()) // EOF never overtakes blocked audio.
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("upload stalled"))
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
        start()
        oldStream.onStreamReady()
        oldStream.onTranscriptionResult(TranscriptSegment("late words", false))
        oldStream.onStreamClosed()
        advance(VoiceInputManager.DRAIN_TIMEOUT_MS)
        assertTrue(manager.isRecording)
        assertTrue(inserted.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun stopDeadlineIsNotResetBySetupUploadEofOrAFinal() {
        start(ready = false); audio(containsSpeech = true)
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        advance(11_000)
        sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        stream.onStreamReady()
        advance(4_000)
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
        advance(25)
        assertEquals(listOf("audio:1", "finish"), sent)
        final("confirmed words")
        advance(44_974)
        assertTrue(manager.hasPendingProcessing())
        advance(1)
        assertTrue(manager.isIdle)
        assertFalse(processing)
        assertEquals(listOf("confirmed words"), inserted)
        assertTrue(errors.single().contains("too long to finish"))
    }

    @Test fun completedStopCannotTimeOutANewerRecording() {
        ApplicationProvider.getApplicationContext<Context>().prefs().edit().putInt("voice_auto_stop_silence_seconds", 300).commit()
        start(); manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        start()
        advance(VoiceInputManager.STOP_TIMEOUT_MS + 1)
        assertTrue(manager.isRecording)
        assertFalse(processing)
        assertTrue(errors.isEmpty())
        assertTrue(markers.isEmpty())
    }

    @Test fun cancellationFromTheStopStateListenerCannotRestoreTheSpinner() {
        start(); audio()
        onStateChange = { state -> if (state == VoiceInputManager.State.IDLE) manager.cancelRecording() }
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.isIdle)
        assertFalse(processing)
        assertEquals(listOf("audio:1"), sent)
        assertTrue(markers.isEmpty())
        advance(VoiceInputManager.STOP_TIMEOUT_MS + 1)
        assertTrue(errors.isEmpty())
    }

    @Test fun failureDuringRecorderStopCannotRestoreTheSpinnerOrQueueEof() {
        start(); audio(containsSpeech = true)
        onRecorderStop = {
            onRecorderStop = null
            capture.onRecordingError("Microphone capture did not stop")
        }
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.isIdle)
        assertFalse(processing)
        assertEquals(listOf("audio:1"), sent)
        assertEquals(1, markers.size)
        assertEquals(listOf("Microphone capture did not stop"), errors)
        advance(VoiceInputManager.STOP_TIMEOUT_MS + 1)
        assertEquals(1, errors.size)
    }

    @Test fun silentCaptureDoesNotStartATranscriptProgressDeadline() {
        start(); final("confirmed"); audio(0)
        advance(29_000)
        assertTrue(manager.isRecording)
        assertTrue(errors.isEmpty())
    }

    @Test fun speechOnsetPostedBeforeStopIsRetainedThroughTheTailBarrier() {
        start(); manager.stopRecording()
        audio(containsSpeech = true); capture.onSpeechStarted()
        shadowOf(Looper.getMainLooper()).idle()
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
    }

    @Test fun silentHangoverAfterAFinalDoesNotInventMissingSpeech() {
        start(); speaking = true; audio(); final("All spoken words.")
        // The recorder still reports speaking until its longer silence window ends.
        audio(0, containsSpeech = false)
        speaking = false; capture.onSpeechStopped()
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle(); stream.onStreamClosed()
        assertEquals(listOf("All spoken words."), inserted)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun onlyQueuedSocketSilenceDoesNotPreventAcceptingACompleteFinal() {
        start(); speaking = true; audio()
        speaking = false; capture.onSpeechStopped()
        transportQueuedSilence = true; audio(0)
        final("All spoken words.")
        transportQueuedSilence = false
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle(); stream.onStreamClosed()
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun onlyQueuedManagerSilenceDoesNotPreventAcceptingACompleteFinal() {
        start(); speaking = true; audio()
        speaking = false; capture.onSpeechStopped()
        sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE; audio(0)
        final("All spoken words.")
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED
        advance(30); stream.onStreamClosed()
        assertEquals(listOf("audio:1", "audio:0", "finish"), sent)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun capturedSpeechEvidenceSurvivesALaterRecorderStateChange() {
        start(); speaking = false; audio(containsSpeech = true)
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle(); stream.onStreamClosed()
        assertEquals(1, markers.size)
    }

    @Test fun speechAfterAFinalStillRequiresItsOwnFinal() {
        start(); speaking = true; audio(); final("First sentence.")
        audio(0, containsSpeech = false)
        audio(2, containsSpeech = true)
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle(); stream.onStreamClosed()
        assertEquals(listOf("First sentence."), inserted)
        assertEquals(1, markers.size)
    }

    @Test fun silenceAutoStopWithoutWordsClosesNormallyAndAllowsANewSession() {
        start(); audio(0)
        advance(30_000)
        assertEquals(VoiceInputManager.State.IDLE, manager.state)
        assertEquals("finish", sent.last())
        assertFalse(manager.isIdle) // Still receiving finals during the close grace.
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
        assertFalse(processing)
        start()
        assertTrue(manager.isRecording)
    }

    @Test fun aSingleNoisyChunkAfterAFinalDoesNotTurnSilenceAutoStopIntoFailure() {
        start(); audio(containsSpeech = true); capture.onSpeechStarted(); final("Confirmed words.")
        capture.onSpeechStopped()
        advance(700); audio(containsSpeech = true)
        advance(29_300)
        assertEquals("finish", sent.last())
        advance(40) // The missing-final timer fired here in the device log.
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertEquals(listOf("Confirmed words."), inserted)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun quietNoiseWithoutRecognizedWordsDoesNotArmAFinalDeadlineWhilePaused() {
        start(); audio(containsSpeech = true)
        manager.pauseRecording(); shadowOf(Looper.getMainLooper()).idle()
        advance(35_000)
        assertTrue(manager.isPaused)
        assertTrue(errors.isEmpty())
        assertTrue(markers.isEmpty())
    }

    @Test fun silenceAutoStopWinsWhenAResponseWatchdogHasTheSameDeadline() {
        start(); audio(); stream.onInterimTranscription(); stream.onServerResponse(false)
        audio(); capture.onSpeechStopped()
        advance(30_000)
        assertEquals("finish", sent.last())
        stream.onStreamClosed()
        assertTrue(manager.isIdle)
        assertTrue(inserted.isEmpty()) // Never promote a provisional transcript.
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun silenceAutoStopStillAcceptsTheLastFinalDuringDrain() {
        start(); audio(containsSpeech = true); capture.onSpeechStopped()
        advance(30_000)
        advance(1000); final("Last confirmed sentence."); stream.onStreamClosed()
        assertEquals(listOf("Last confirmed sentence."), inserted)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun aRealNetworkFailureDuringSilenceDrainStillReportsAnInterruption() {
        start(); audio(); advance(30_000)
        network.loss!!()
        assertTrue(manager.isIdle)
        assertEquals(1, markers.size)
        assertTrue(errors.single().contains("Internet connection lost"))
    }

    @Test fun manualStopClearsThePreviousSpeechDeadlineAndWaitsForTheDrainFinal() {
        start(); audio(); stream.onInterimTranscription(); stream.onServerResponse(false)
        manager.pauseRecording(); shadowOf(Looper.getMainLooper()).idle()
        advance(29_000); manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle()
        advance(1100)
        assertFalse(manager.isIdle)
        assertTrue(errors.isEmpty())
        final("Confirmed during drain."); stream.onStreamClosed()
        assertEquals(listOf("Confirmed during drain."), inserted)
        assertTrue(markers.isEmpty())
    }

    @Test fun repeatedFinalProgressAfterSocketSpeechDrainsDoesNotInsertDuplicateText() {
        start(); audio(containsSpeech = true)
        transportQueued = true; final("Confirmed sentence.")
        transportQueued = false
        stream.onServerResponse(true) // Client deduplicated the repeated authoritative final.
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle(); stream.onStreamClosed()
        assertEquals(listOf("Confirmed sentence."), inserted)
        assertTrue(markers.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun repeatedFinalProgressCannotAcknowledgeSpeechStillQueuedInTheSocket() {
        start(); audio(containsSpeech = true)
        transportQueued = true; final("Confirmed sentence."); stream.onServerResponse(true)
        transportQueued = false
        manager.stopRecording(); shadowOf(Looper.getMainLooper()).idle(); stream.onStreamClosed()
        assertEquals(listOf("Confirmed sentence."), inserted)
        assertEquals(1, markers.size)
    }

    @Test fun aBackpressuredOlderBoundaryDoesNotStartAFinalWaitDuringTheNextUtterance() {
        start(); audio(containsSpeech = true); capture.onSpeechStarted()
        stream.onInterimTranscription(); stream.onServerResponse(false)
        sendResult = MuseTranscriptionClient.AudioSendResult.BACKPRESSURE
        audio(); capture.onSpeechStopped()
        advance(1000); audio(containsSpeech = true); capture.onSpeechStarted()
        sendResult = MuseTranscriptionClient.AudioSendResult.ACCEPTED; advance(30)
        repeat(35) {
            advance(1000); audio(containsSpeech = true)
            stream.onInterimTranscription(); stream.onServerResponse(false)
        }
        assertTrue(manager.isRecording)
        assertTrue(errors.isEmpty())
        capture.onSpeechStopped(); advance(1000); final("Both utterances.")
        assertEquals(listOf("Both utterances."), inserted)
        assertTrue(markers.isEmpty())
    }

    private class FakeNetwork : VoiceNetworkMonitor {
        var available = true
        var loss: (() -> Unit)? = null
        override fun start(onUnavailable: () -> Unit): Boolean { loss = onUnavailable; return available }
        override fun stop() { }
    }
}
