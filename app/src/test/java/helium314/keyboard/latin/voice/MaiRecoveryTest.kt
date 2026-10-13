// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Looper
import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig
import java.time.Duration
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MaiRecoveryTest {
    private lateinit var client: MaiTranscriptionClient
    private val sessions = mutableListOf<FakeMaiSpeechSession>()
    private val events = mutableListOf<String>()
    private var duringRecovery: (() -> Unit)? = null
    private val active get() = sessions.last()
    private fun pump(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Before fun setUp() {
        client = MaiTranscriptionClient({ FakeMaiSpeechSession().also { sessions.add(it) } },
            platformError = { null })
        client.startStreaming(MaiConfig("test-key", "centralus", false), "en-US", object : MaiTranscriptionClient.StreamingCallback {
            override fun onStreamReady() { events.add("ready") }
            override fun onServiceConnected() { events.add("connected") }
            override fun onStreamRecovering() { events.add("recovering"); duringRecovery?.invoke() }
            override fun onTranscriptionResult(segment: TranscriptSegment) { events.add("text:${segment.text}") }
            override fun onPendingProcessingChanged() {}
            override fun onStreamError(error: String, incomplete: Boolean) { events.add("error:$incomplete:$error") }
            override fun onAudioWriteAvailable() { events.add("capacity") }
            override fun onFinalConfirmationStalled() {}
            override fun onStreamClosed() { events.add("closed") }
            override fun onStreamDrainRequired() {}
        })
    }
    @After fun tearDown() { client.cancelAll() }
    private fun ready() { active.ready(); pump() }
    private fun audio(value: Int, size: Int = 3200, speech: Boolean = true) {
        assertTrue(client.sendAudioChunk(ByteArray(size) { value.toByte() }, speech))
    }
    @Test fun localStartupIsNotRemoteReadiness() {
        active.listener.onReady(); pump()
        assertEquals(listOf("ready"), events)
        audio(1)
        active.connected(); pump()
        assertTrue("connected" in events)
    }
    @Test fun transportReconnectWithinGracePreservesRecognizerAndDoesNotFail() {
        ready(); audio(1); active.disconnected(); pump()
        assertFalse(client.sendAudioChunk(ByteArray(3200)))
        assertEquals(1, events.count { it == "recovering" })
        pump(1000); active.connected(); pump(); pump(2500)
        assertEquals(1, sessions.size)
        active.final("Kept."); pump(); client.finishStreaming(); active.ended(); pump()
        assertEquals(listOf("text:Kept."), events.filter { it.startsWith("text:") })
        assertFalse(events.any { it.startsWith("error:") }); assertFalse(client.hasPendingProcessing)
    }
    @Test fun replacementReplaysOnlySuffixAndRemapsFinalOffsetsWithoutDuplicatingPrefix() {
        ready(); audio(1); active.final("Prefix.", id = "prefix", token = 0); pump(); audio(2)
        val failed = active
        failed.error(); pump(); pump(500); ready()
        assertEquals(2, sessions.size); assertTrue(failed.closed)
        assertEquals(listOf(2.toByte()), active.audio.map { it[0] })
        failed.final("Stale."); failed.ended(); pump()
        active.final("Suffix.", token = 0); pump()
        assertFalse(client.hasUnconfirmedSpeech)
        client.finishStreaming(); active.ended(); pump()
        assertEquals(listOf("text:Prefix.", "text:Suffix."), events.filter { it.startsWith("text:") })
        assertFalse(events.any { it.startsWith("error:") })
    }
    @Test fun partialChunkOffsetCutsReplayAtACompletePcmSample() {
        ready(); audio(1); audio(2)
        active.final("Prefix.", end = 1601, token = 0); pump()
        active.error(); pump(); pump(500); ready()
        assertEquals(listOf(1600, 3200), active.audio.map { it.size })
        assertEquals(listOf(1.toByte(), 2.toByte()), active.audio.map { it[0] })
        active.final("Rest.", token = 0); pump()
        assertFalse(client.hasUnconfirmedSpeech)
    }
    @Test fun drainRecoveryKeepsOriginalStopDeadlineAndReleasesPendingState() {
        ready(); audio(1); client.finishStreaming(); pump(19000)
        active.error(); pump(); pump(500); ready()
        client.finishStreaming(); pump(501)
        assertTrue(events.any { it.startsWith("error:true:") && it.contains("draining") })
        assertFalse(client.hasPendingProcessing); assertTrue(active.closed)
    }
    @Test fun retryBudgetIsFiniteAndDoesNotRestartOnLateCallbacks() {
        ready(); audio(1)
        active.error(); pump(); pump(500); ready()
        active.error(); pump(); pump(1500); ready()
        val last = active
        active.error(); pump(); pump(30000)
        assertEquals(3, sessions.size); assertFalse(client.hasPendingProcessing)
        assertEquals(1, events.count { it.startsWith("error:") })
        last.final("Too late."); last.connected(); pump()
        assertFalse(events.any { it.startsWith("text:") })
    }
    @Test fun replacementCannotHangAtLocalStartupOrExtendRecoveryWithDisconnects() {
        ready(); audio(1); active.error(); pump(); pump(500)
        active.listener.onReady(); pump()
        repeat(5) { active.disconnected(); pump(300) }
        pump(20000)
        assertTrue(events.any { it.startsWith("error:") })
        assertFalse(client.hasPendingProcessing); assertTrue(sessions.size <= 3)
    }
    @Test fun authenticationFailureDoesNotRetry() {
        active.error("AuthenticationFailure", retryable = false); pump(); pump(30000)
        assertEquals(1, sessions.size); assertTrue(active.closed)
        assertTrue(events.any { it.startsWith("error:false:") })
    }
    @Test fun quietTailFailureDoesNotClaimUnfinalizedSpeech() {
        ready(); audio(1); active.final("Complete.", token = 0); pump(); audio(0, speech = false)
        active.error("Nonretryable", retryable = false); pump()
        assertTrue(events.any { it.startsWith("error:false:") })
    }
    @Test fun missingFinalOffsetDisablesUnsafeReplayOfVisibleText() {
        ready(); audio(1); active.final("Visible.", end = 0, token = 0); pump()
        active.error(); pump(); pump(3000)
        assertEquals(1, sessions.size); assertTrue(events.any { it.startsWith("error:") })
        assertEquals(listOf("text:Visible."), events.filter { it.startsWith("text:") })
    }
    @Test fun suffixBeyondCapacityFailsWithoutSkippingOldAudio() {
        ready(); repeat(301) { audio(1) }
        active.error(); pump(); pump(3000)
        assertEquals(1, sessions.size)
        assertTrue(events.any { it.contains("safe recovery buffer") })
    }
    @Test fun replayBackpressureResumesInOrderBeforeWriteReadiness() {
        ready(); repeat(300) { audio(it) }
        active.error(); pump(); pump(500)
        active.deferWrites = true; active.ready(); pump()
        assertEquals(1, events.count { it == "ready" })
        repeat(5) { active.runWorker(); pump() }
        assertEquals(300, active.audio.size)
        assertEquals((0 until 300).map { it.toByte() }, active.audio.map { it[0] })
        assertEquals(2, events.count { it == "ready" })
    }
    @Test fun explicitCancelSuppressesScheduledReplacementAndClearsAudio() {
        ready(); audio(1); active.error(); pump(); client.cancelAll(); pump(30000)
        assertEquals(1, sessions.size); assertFalse(client.hasPendingProcessing)
    }
    @Test fun reentrantEditorCancellationDuringRecoveryCannotOpenAnotherRecognizer() {
        ready(); audio(1); duringRecovery = { client.cancelAll() }
        active.error(); pump(); pump(30000)
        assertEquals(1, sessions.size); assertFalse(client.hasPendingProcessing)
        assertFalse(events.any { it.startsWith("error:") })
    }
    @Test fun normalEofDisconnectDoesNotTriggerRecoveryOrAnotherReady() {
        ready(); audio(0, speech = false); client.finishStreaming()
        active.disconnected(); active.connected(); active.ended(); pump()
        assertFalse("recovering" in events); assertEquals(1, events.count { it == "ready" })
        assertEquals("closed", events.last())
    }
    @Test fun replayBufferTrimsPartialChunksAndDetectsEvictedPrefix() {
        val replay = PendingAudioReplay(8)
        replay.add(0, byteArrayOf(1, 2, 3, 4)); replay.add(4, byteArrayOf(5, 6, 7, 8))
        replay.confirm(2)
        assertEquals(listOf(listOf<Byte>(3, 4), listOf<Byte>(5, 6, 7, 8)), replay.suffix(2, 8)!!.map { it.toList() })
        replay.add(8, byteArrayOf(9, 10, 11, 12))
        assertNull(replay.suffix(2, 12))
        assertEquals(listOf(listOf<Byte>(5, 6, 7, 8), listOf<Byte>(9, 10, 11, 12)), replay.suffix(4, 12)!!.map { it.toList() })
    }
}
