// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.prefs
import java.time.Duration
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class VoiceInputManagerTest {
    private lateinit var manager: VoiceInputManager
    private lateinit var recording: VoiceRecorder.RecordingCallback
    private lateinit var network: FakeVoiceNetworkMonitor
    private lateinit var recorder: VoiceRecorder
    private val sessions = mutableListOf<FakeMaiSpeechSession>()
    private val events = mutableListOf<String>()
    private val active get() = sessions.last()
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.prefs().edit().clear().commit()
        TranscriptionPreferences.writeMaiApiKey(context.prefs(), "test-key")
        recorder = Mockito.mock(VoiceRecorder::class.java)
        Mockito.`when`(recorder.hasRecordPermission()).thenReturn(true)
        Mockito.`when`(recorder.startRecording()).thenReturn(true)
        Mockito.doAnswer { recording = it.getArgument(0); null }
            .`when`(recorder).setCallback(Mockito.any(VoiceRecorder.RecordingCallback::class.java))
        val client = MaiTranscriptionClient({ FakeMaiSpeechSession().also { sessions.add(it) } }, platformError = { null }, progressTimeoutMs = 120_000L)
        network = FakeVoiceNetworkMonitor()
        manager = VoiceInputManager(context, recorder, client, network)
        manager.setListener(object : VoiceInputManager.VoiceInputListener {
            override fun onStateChanged(state: VoiceInputManager.State) { events.add("state:$state") }
            override fun onTranscriptionResult(text: String, attachesToPrevious: Boolean) { events.add("text:$text") }
            override fun onProcessingStarted() { events.add("processing") }
            override fun onProcessingIdle() { events.add("idle") }
            override fun onPendingProcessingCancelled() { events.add("cancelled") }
            override fun onError(error: String) { events.add("error:$error") }
            override fun onTranscriptionInterrupted() { events.add("gap") }
            override fun onPermissionRequired() { events.add("permission") }
        })
    }
    @After fun tearDown() { manager.destroy() }
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun start() { assertTrue(manager.startRecording()); active.ready(); pump() }
    private fun end(text: String) { active.final(text); active.ended(); pump() }
    @Test fun stopBeforeReadyPreservesQueuedAudioUntilSdkEof() {
        assertTrue(manager.startRecording())
        recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        assertTrue(manager.hasPendingProcessing()); assertTrue(active.audio.isEmpty())
        active.ready(); pump()
        assertTrue(active.inputFinished); assertEquals(1, active.audio.size)
        end("Last word.")
        assertTrue(manager.isIdle); assertFalse(manager.hasPendingProcessing())
        assertTrue(events.indexOf("text:Last word.") < events.lastIndexOf("idle"))
    }
    @Test fun silenceBeforeReadyRequestsAFinalWhenSdkStarts() {
        manager.startRecording(); recording.onAudioChunk(byteArrayOf(1, 0)); recording.onSpeechStopped()
        active.ready(); pump()
        assertEquals(1, active.commits.size)
        active.final("Complete sentence."); pump()
        assertTrue("text:Complete sentence." in events); assertTrue(manager.isRecording)
    }
    @Test fun pauseDuringStartupAndResumeKeepsActiveSpeech() {
        manager.startRecording(); recording.onAudioChunk(byteArrayOf(1, 0))
        manager.pauseRecording(); manager.resumeRecording(); active.ready(); pump()
        assertTrue(active.commits.isEmpty()); assertEquals(1, active.audio.size)
        manager.stopRecording(); pump(); end("Continued speech.")
        assertTrue(manager.isIdle); assertTrue("text:Continued speech." in events)
    }
    @Test fun pauseCommitsAndResumeKeepsSameSdkSession() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.pauseRecording(); pump()
        assertEquals(1, active.commits.size); active.final("First."); pump()
        manager.resumeRecording(); recording.onAudioChunk(byteArrayOf(2, 0))
        assertEquals(1, sessions.size); manager.stopRecording(); pump(); end("Second.")
        assertEquals(listOf("text:First.", "text:Second."), events.filter { it.startsWith("text:") })
    }
    @Test fun startupConnectionFailureStopsWithoutRetryAndMarksBufferedSpeech() {
        manager.startRecording(); recording.onAudioChunk(byteArrayOf(1, 0))
        val broken = active
        broken.listener.onError("Connection failed."); pump()
        assertTrue(manager.isIdle); assertFalse(manager.hasPendingProcessing())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(1, sessions.size); assertEquals(1, events.count { it == "gap" })
        broken.final("Late reconnected text."); pump()
        assertFalse(events.any { it.startsWith("text:") })
    }
    @Test fun rotationWaitsForEofAndBuffersNewSpeech() {
        start(); recording.onSpeechStarted(); recording.onAudioChunk(byteArrayOf(1, 0))
        active.final("", id = "idle"); pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MaiTranscriptionClient.SESSION_ROTATE_AFTER_MS))
        assertTrue(active.inputFinished); recording.onAudioChunk(byteArrayOf(2, 0))
        assertEquals(1, sessions.size); end("Outgoing phrase.")
        assertEquals(2, sessions.size); active.ready(); pump()
        assertEquals(1, active.audio.size); manager.stopRecording(); pump(); end("Replacement phrase.")
        assertTrue(manager.isIdle)
        assertEquals(listOf("text:Outgoing phrase.", "text:Replacement phrase."), events.filter { it.startsWith("text:") })
    }
    @Test fun stopAfterSilenceKeepsAudioBeforeVadDetectsNewSpeech() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); recording.onSpeechStopped(); pump()
        active.final("First phrase."); pump(); manager.stopRecording()
        recording.onAudioChunk(byteArrayOf(2, 0)); pump()
        assertEquals(2, active.audio.size); end("Last word.")
        assertTrue(manager.isIdle); assertTrue("text:Last word." in events)
    }
    @Test fun cancellationSuppressesQueuedFinalsAndEof() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        active.final("Discarded."); active.ended(); manager.cancelRecording(); pump()
        assertTrue(manager.isIdle); assertFalse(manager.hasPendingProcessing())
        assertFalse(events.any { it.startsWith("text:") }); assertTrue("cancelled" in events)
    }
    @Test fun restartDuringDrainCapturesImmediatelyAndPreservesBothSessionsInOrder() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        val outgoing = active
        assertTrue(outgoing.inputFinished)
        assertTrue(manager.startRecording()); assertTrue(manager.isRecording)
        recording.onAudioChunk(byteArrayOf(2, 0))
        assertEquals(1, sessions.size); assertEquals(1, outgoing.audio.size)
        outgoing.final("Outgoing phrase."); outgoing.ended(); pump()
        assertEquals(2, sessions.size); active.ready(); pump()
        assertEquals(listOf<Byte>(2, 0), active.audio.single().toList())
        manager.stopRecording(); pump(); end("New phrase.")
        assertTrue(manager.isIdle)
        assertEquals(listOf("text:Outgoing phrase.", "text:New phrase."), events.filter { it.startsWith("text:") })
        assertFalse("cancelled" in events)
    }
    @Test fun secondStopDuringRestartDrainStillDeliversTheNewRecording() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        manager.toggleRecording(); recording.onAudioChunk(byteArrayOf(2, 0)); manager.toggleRecording(); pump()
        assertEquals(VoiceInputManager.State.IDLE, manager.state)
        end("First."); active.ready(); pump()
        assertTrue(active.inputFinished); assertEquals(1, active.audio.size)
        end("Second."); assertTrue(manager.isIdle)
        assertEquals(listOf("text:First.", "text:Second."), events.filter { it.startsWith("text:") })
    }
    @Test fun microphoneInitializationFailureDuringRestartPreservesOutgoingFinals() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        val outgoing = active
        Mockito.doAnswer {
            recording.onRecordingError("Failed to initialize audio recording")
            false
        }.`when`(recorder).startRecording()
        assertFalse(manager.startRecording()); pump()
        assertFalse(outgoing.closed); assertTrue(manager.hasPendingProcessing())
        end("Preserved outgoing phrase.")
        assertTrue(manager.isIdle); assertEquals(1, sessions.size)
        assertTrue("text:Preserved outgoing phrase." in events)
        assertEquals(1, events.count { it.startsWith("error:") }); assertFalse("cancelled" in events)
    }
    @Test fun failedRestartWithoutRecorderErrorAlsoPreservesOutgoingFinals() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        Mockito.`when`(recorder.startRecording()).thenReturn(false)
        assertFalse(manager.startRecording()); pump()
        assertFalse(active.closed); end("Preserved phrase.")
        assertTrue(manager.isIdle); assertTrue("text:Preserved phrase." in events)
        assertEquals(1, events.count { it.startsWith("error:") }); assertFalse("cancelled" in events)
    }
    @Test fun failedRestartBeforeInputClosesDrainsTheExistingSession() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording()
        Mockito.`when`(recorder.startRecording()).thenReturn(false)
        assertFalse(manager.startRecording()); pump()
        assertTrue(active.inputFinished); assertFalse(active.closed)
        end("Tail before restart.")
        assertTrue(manager.isIdle); assertTrue("text:Tail before restart." in events)
        assertFalse("cancelled" in events)
    }
    @Test fun emptyReadFailureDuringRestartDrainsOutgoingAndBufferedSpeechInOrder() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        val outgoing = active
        assertTrue(manager.startRecording()); recording.onAudioChunk(byteArrayOf(2, 0))
        recording.onRecordingError("Microphone produced no readable audio"); pump()
        assertEquals(VoiceInputManager.State.IDLE, manager.state)
        assertFalse(outgoing.closed); end("Outgoing phrase.")
        active.ready(); pump()
        assertTrue(active.inputFinished); assertEquals(listOf<Byte>(2, 0), active.audio.single().toList())
        end("Buffered phrase.")
        assertTrue(manager.isIdle)
        assertEquals(listOf("text:Outgoing phrase.", "text:Buffered phrase."), events.filter { it.startsWith("text:") })
        assertEquals(1, events.count { it.startsWith("error:") }); assertFalse("cancelled" in events)
    }
    @Test fun restartBufferOverflowPreservesOutgoingAndAcceptedBufferedAudio() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        val outgoing = active
        assertTrue(manager.startRecording())
        repeat(302) { recording.onAudioChunk(byteArrayOf(2, 0)) }; pump()
        assertEquals(VoiceInputManager.State.IDLE, manager.state)
        assertFalse(outgoing.closed); end("Outgoing phrase.")
        active.ready(); pump()
        assertEquals(300, active.audio.size); assertTrue(active.inputFinished)
        end("Buffered speech.")
        assertTrue(manager.isIdle)
        assertEquals(listOf("text:Outgoing phrase.", "text:Buffered speech."), events.filter { it.startsWith("text:") })
        assertEquals(1, events.count { it.startsWith("error:") }); assertFalse("cancelled" in events)
    }
    @Test fun restartBeforeStartupFinishesKeepsTheBufferedTailAndContinuesTheOpenSession() {
        manager.startRecording(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording()
        assertTrue(manager.startRecording()); recording.onAudioChunk(byteArrayOf(2, 0)); pump()
        active.ready(); pump()
        assertEquals(1, sessions.size); assertEquals(2, active.audio.size)
        assertFalse(active.inputFinished); assertTrue(manager.isRecording)
        manager.stopRecording(); pump(); end("Both phrases."); assertTrue(manager.isIdle)
    }
    @Test fun restartWithInputStillOpenPreservesTheOriginalRotationDeadline() {
        start(); recording.onSpeechStarted(); recording.onAudioChunk(byteArrayOf(1, 0))
        active.final("", id = "idle"); pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(54))
        manager.stopRecording(); assertTrue(manager.startRecording()); recording.onSpeechStarted(); pump()
        assertFalse(active.inputFinished); assertEquals(1, sessions.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(active.inputFinished); assertTrue(manager.isRecording)
    }
    @Test fun missingCommitAcknowledgementDrainsAndResumesWithoutCancellingTheMicrophone() {
        start(); recording.onSpeechStarted(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.pauseRecording(); pump()
        active.final("Complete phrase.", end = 0, token = 0); pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertTrue(active.inputFinished); assertTrue(manager.isPaused)
        manager.resumeRecording(); recording.onAudioChunk(byteArrayOf(2, 0))
        active.final("Tail.", id = "tail"); active.ended(); pump()
        assertEquals(2, sessions.size); active.ready(); pump()
        assertEquals(1, active.audio.size); manager.stopRecording(); pump(); end("Continued.")
        assertTrue(manager.isIdle); assertFalse(events.any { it.startsWith("error:") })
        assertEquals(listOf("text:Complete phrase.", "text:Tail.", "text:Continued."), events.filter { it.startsWith("text:") })
    }
    @Test fun startupBufferOverflowStopsCaptureAndDrainsAcceptedSpeech() {
        manager.startRecording()
        repeat(301) { recording.onAudioChunk(byteArrayOf(1, 0)) }; pump()
        assertEquals(VoiceInputManager.State.IDLE, manager.state)
        assertTrue(events.any { it.contains("audio buffer filled") }); assertFalse(active.closed)
        active.ready(); pump()
        assertEquals(300, active.audio.size); assertTrue(active.inputFinished)
        end("Accepted speech."); assertTrue(manager.isIdle)
        assertTrue("text:Accepted speech." in events); assertFalse("cancelled" in events)
    }
    @Test fun subsecondSilenceSettingReachesRecorderWithoutRounding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TranscriptionPreferences.writeVoiceChunkSilenceMs(context.prefs(), 750)
        manager.startRecording()
        Mockito.verify(recorder).updateSilenceConfig(750L, 100.0)
        val realRecorder = VoiceRecorder(context)
        realRecorder.updateSilenceConfig(750L, 100.0)
        val config = ReflectionHelpers.getField<Any>(realRecorder, "silenceConfig")
        assertEquals(750L, ReflectionHelpers.getField<Long>(config, "silenceDurationMs"))
        assertEquals(100.0, ReflectionHelpers.getField<Double>(config, "silenceThreshold"))
    }
    @Test fun offlineStartDoesNotOpenMicrophoneOrCreateSession() {
        network.available = false
        assertFalse(manager.startRecording())
        Mockito.verify(recorder, Mockito.never()).startRecording()
        assertTrue(sessions.isEmpty()); assertTrue(manager.isIdle)
        assertTrue(events.any { it.contains("No validated internet") })
    }
    @Test fun networkLossStopsAndMarksGapOnceAndNeverInsertsReconnectedFinals() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0))
        active.final("Confirmed prefix."); pump()
        recording.onAudioChunk(byteArrayOf(2, 0))
        val broken = active
        val staleNetworkCallback = network.unavailable!!
        network.loseConnection()
        assertTrue(manager.isIdle); assertTrue(broken.closed)
        recording.onAudioChunk(byteArrayOf(3, 0)); broken.final("Reconnected suffix."); pump()
        assertEquals(listOf("text:Confirmed prefix."), events.filter { it.startsWith("text:") })
        assertEquals(1, events.count { it == "gap" })
        assertEquals(1, sessions.size)
        network.available = true
        assertTrue(manager.startRecording())
        staleNetworkCallback(); assertTrue(manager.isRecording)
        active.ready(); pump(); recording.onAudioChunk(byteArrayOf(4, 0))
        manager.stopRecording(); pump(); end("Explicit new dictation.")
        assertTrue(events.indexOf("gap") < events.indexOf("text:Explicit new dictation."))
    }
    @Test fun sdkFailureStopsEvenWhenAllEarlierAudioWasFinalized() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); active.final("Prefix."); pump()
        active.listener.onError("Disconnected."); pump()
        assertTrue(manager.isIdle); assertEquals(1, sessions.size)
        assertFalse(events.any { it == "gap" }); assertTrue(events.any { it.contains("Disconnected") })
        Mockito.verify(recorder).stopRecording()
    }
    @Test fun localCaptureFailureBlocksRestartUntilTailAndGapAreInserted() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0))
        recording.onRecordingError("Microphone read failed.")
        assertFalse(manager.startRecording())
        recording.onAudioChunk(byteArrayOf(2, 0)); pump()
        assertEquals(1, active.audio.size)
        assertTrue(manager.hasPendingProcessing()); assertTrue("processing" in events)
        end("Accepted tail.")
        assertTrue(events.indexOf("text:Accepted tail.") < events.indexOf("gap"))
        assertEquals(1, events.count { it == "gap" })
        assertTrue(manager.startRecording())
    }
    @Test fun fullSizedStartupOverflowDrainsAll300ChunksThroughBoundedWorker() {
        manager.startRecording(); active.deferWrites = true
        repeat(301) { index -> recording.onAudioChunk(ByteArray(3200) { index.toByte() }) }
        pump(); active.ready(); pump()
        assertFalse(active.inputFinished)
        repeat(5) { active.runWorker(); pump() }
        assertTrue(active.inputFinished); assertEquals(300, active.audio.size)
        assertEquals((0 until 300).map { it.toByte() }, active.audio.map { it[0] })
        end("Accepted audio.")
        assertTrue(manager.isIdle); assertEquals(1, events.count { it.startsWith("error:") })
        assertFalse("cancelled" in events)
    }
    @Test fun backpressureDefersSilenceCommitAndEofUntilEntireFifoIsAccepted() {
        start(); active.backpressure = true
        repeat(3) { index -> recording.onAudioChunk(ByteArray(3200) { index.toByte() }) }
        recording.onSpeechStopped(); assertTrue(active.commits.isEmpty())
        active.backpressure = false; active.listener.onWriteAvailable(); pump()
        assertEquals(9600L, active.commits.single().second)
        active.backpressure = true; recording.onSpeechStarted(); recording.onAudioChunk(ByteArray(3200))
        manager.stopRecording(); pump(); assertFalse(active.inputFinished)
        active.backpressure = false; active.listener.onWriteAvailable(); pump()
        assertTrue(active.inputFinished); assertEquals(4, active.audio.size)
    }
    @Test fun deferredSilenceCommitDoesNotIncludeSpeechCapturedAfterThePause() {
        start(); active.backpressure = true
        repeat(3) { recording.onAudioChunk(ByteArray(3200)) }
        recording.onSpeechStopped()
        recording.onAudioChunk(ByteArray(3200)); recording.onSpeechStarted()
        recording.onAudioChunk(ByteArray(3200))
        active.backpressure = false; active.listener.onWriteAvailable(); pump()
        assertEquals(9600L, active.commits.single().second)
        assertEquals(5, active.audio.size)
    }
    @Test fun deferredManualPauseCommitPrecedesResumedAudio() {
        start(); active.backpressure = true
        recording.onAudioChunk(ByteArray(3200)); manager.pauseRecording(); pump()
        manager.resumeRecording(); recording.onAudioChunk(ByteArray(3200))
        active.backpressure = false; active.listener.onWriteAvailable(); pump()
        assertEquals(3200L, active.commits.single().second)
        assertEquals(2, active.audio.size)
    }
    @Test fun networkLossDuringReplacementDrainDiscardsNewAudioAndPreventsReplacement() {
        start(); recording.onAudioChunk(byteArrayOf(1, 0)); manager.stopRecording(); pump()
        val outgoing = active
        manager.startRecording(); recording.onAudioChunk(byteArrayOf(2, 0))
        network.loseConnection(); outgoing.final("Late tail."); outgoing.ended(); pump()
        assertTrue(manager.isIdle); assertEquals(1, sessions.size)
        assertEquals(1, events.count { it == "gap" }); assertFalse(events.any { it.startsWith("text:") })
    }

    @Test fun workerStallWhileStoppingHasABoundedDeadlineAndCannotResumeAfterCapacityReturns() {
        start(); active.backpressure = true
        recording.onAudioChunk(ByteArray(3200)); manager.stopRecording(); pump()
        val failed = active
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(manager.isIdle); assertTrue(failed.closed)
        assertEquals(1, events.count { it == "gap" })
        failed.backpressure = false; failed.listener.onWriteAvailable(); failed.final("Late."); pump()
        assertTrue(failed.audio.isEmpty()); assertFalse(events.any { it.startsWith("text:") })
        assertTrue(events.any { it.contains("waited too long to upload") })
    }

    @Test fun finalConfirmationStallStopsCaptureAndBlocksRestartButPreservesAcceptedSpeechToEof() {
        manager.destroy(); events.clear(); sessions.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = MaiTranscriptionClient({ FakeMaiSpeechSession().also { sessions.add(it) } }, platformError = { null })
        manager = VoiceInputManager(context, recorder, client, network)
        manager.setListener(object : VoiceInputManager.VoiceInputListener {
            override fun onStateChanged(state: VoiceInputManager.State) { events.add("state:$state") }
            override fun onTranscriptionResult(text: String, attachesToPrevious: Boolean) { events.add("text:$text") }
            override fun onProcessingStarted() { events.add("processing") }
            override fun onProcessingIdle() { events.add("idle") }
            override fun onPendingProcessingCancelled() { events.add("cancelled") }
            override fun onError(error: String) { events.add("error:$error") }
            override fun onTranscriptionInterrupted() { events.add("gap") }
            override fun onPermissionRequired() {}
        })
        start(); recording.onSpeechStarted(); recording.onAudioChunk(ByteArray(3200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertFalse(manager.isRecording); assertTrue(manager.hasPendingProcessing())
        assertTrue(active.inputFinished); assertFalse(active.closed)
        assertFalse(manager.startRecording())
        end("Confirmed during drain.")
        assertTrue("text:Confirmed during drain." in events); assertFalse("gap" in events)
        assertTrue(manager.isIdle); assertTrue(manager.startRecording())
    }

    @Test fun quietAudioAfterLocalSilenceStillReachesAzureWithoutWaitingForVadOnset() {
        start(); recording.onAudioChunk(ByteArray(3200) { 1 }); recording.onSpeechStopped()
        repeat(8) { index -> recording.onAudioChunk(ByteArray(3200) { (index + 2).toByte() }) }
        assertEquals(9, active.audio.size)
        assertEquals((1..9).map { it.toByte() }, active.audio.map { it[0] })
        assertTrue(manager.isRecording)
    }

}
