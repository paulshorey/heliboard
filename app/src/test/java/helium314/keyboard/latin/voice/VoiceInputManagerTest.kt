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

@RunWith(RobolectricTestRunner::class)
class VoiceInputManagerTest {
    private lateinit var manager: VoiceInputManager
    private lateinit var recording: VoiceRecorder.RecordingCallback
    private val sessions = mutableListOf<FakeMaiSpeechSession>()
    private val events = mutableListOf<String>()
    private val active get() = sessions.last()
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.prefs().edit().clear().commit()
        TranscriptionPreferences.writeMaiApiKey(context.prefs(), "test-key")
        val recorder = Mockito.mock(VoiceRecorder::class.java)
        Mockito.`when`(recorder.hasRecordPermission()).thenReturn(true)
        Mockito.`when`(recorder.startRecording()).thenReturn(true)
        Mockito.doAnswer { recording = it.getArgument(0); null }
            .`when`(recorder).setCallback(Mockito.any(VoiceRecorder.RecordingCallback::class.java))
        val client = MaiTranscriptionClient({ FakeMaiSpeechSession().also { sessions.add(it) } }, platformError = { null })
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
    @Test fun startupBackoffCanDrainBufferedSpeechAfterStop() {
        manager.startRecording(); recording.onAudioChunk(byteArrayOf(1, 0))
        active.listener.onError("Connection failed.", true); pump(); manager.stopRecording(); pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(2, sessions.size); active.ready(); pump(); end("Buffered phrase.")
        assertTrue(manager.isIdle); assertTrue("text:Buffered phrase." in events)
        assertFalse(events.any { it.startsWith("error:") })
    }
    @Test fun rotationWaitsForEofAndBuffersNewSpeech() {
        start(); recording.onSpeechStarted(); recording.onAudioChunk(byteArrayOf(1, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MaiTranscriptionClient.SESSION_ROTATE_AFTER_MS))
        assertTrue(active.inputFinished); recording.onAudioChunk(byteArrayOf(2, 0))
        assertEquals(1, sessions.size); end("Outgoing phrase.")
        assertEquals(2, sessions.size); active.ready(); pump()
        assertEquals(1, active.audio.size); manager.stopRecording(); pump(); end("Replacement phrase.")
        assertTrue(manager.isIdle)
        assertEquals(listOf("text:Outgoing phrase.", "text:Replacement phrase."), events.filter { it.startsWith("text:") })
    }
    @Test fun stopAfterSilenceKeepsHeldOnsetBeforeVadDetectsNewSpeech() {
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
    @Test fun startupBufferOverflowReportsFailureInsteadOfDroppingSpeech() {
        manager.startRecording()
        repeat(301) { recording.onAudioChunk(byteArrayOf(1, 0)) }
        assertTrue(manager.isIdle); assertTrue(events.any { it.contains("audio buffer filled") })
        assertTrue(active.closed)
    }
}
