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
class MaiTranscriptionClientStreamTest {
    private lateinit var session: FakeMaiSpeechSession
    private lateinit var client: MaiTranscriptionClient
    private val events = mutableListOf<String>()
    @Before fun setUp() {
        session = FakeMaiSpeechSession()
        client = MaiTranscriptionClient({ session }, 1_000L, { null }, progressTimeoutMs = 120_000L)
        client.startStreaming(MaiConfig("test-key", "centralus", false), "en-US", callback())
    }
    @After fun tearDown() { client.cancelAll() }
    private fun callback() = object : MaiTranscriptionClient.StreamingCallback {
        override fun onStreamReady() { events.add("ready") }
        override fun onTranscriptionResult(segment: TranscriptSegment) { events.add("text:${segment.text}") }
        override fun onPendingProcessingChanged() { events.add("pending:${client.hasPendingProcessing}") }
        override fun onStreamError(error: String, incomplete: Boolean) { events.add("error:$incomplete:$error") }
        override fun onAudioWriteAvailable() { events.add("capacity") }
        override fun onFinalConfirmationStalled() { events.add("stalled"); client.finishStreaming() }
        override fun onStreamClosed() { events.add("closed") }
        override fun onStreamDrainRequired() { events.add("drain"); client.finishStreaming() }
    }
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun ready() { session.ready(); pump() }
    @Test fun waitsForSdkStartupAndPassesKeyRegionAndLocale() {
        assertFalse(client.sendAudioChunk(byteArrayOf(1, 0)))
        assertEquals("test-key", session.config.apiKey)
        assertEquals("centralus", session.config.region)
        assertEquals("en-US", session.language)
        ready()
        assertTrue(client.sendAudioChunk(byteArrayOf(1, 0)))
        assertEquals(2, session.audio.single().size)
    }
    @Test fun closesInputAndWaitsForEofAfterAllFinals() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finishStreaming()
        assertTrue(session.inputFinished)
        assertFalse(session.closed)
        assertTrue(client.hasPendingProcessing)
        session.final("Last word."); pump()
        assertTrue(client.hasPendingProcessing)
        assertTrue("text:Last word." in events)
        session.ended(); pump()
        assertFalse(client.hasPendingProcessing)
        assertTrue(session.closed)
        assertEquals("closed", events.last())
    }
    @Test fun commitsOnlyNewNonemptyAudioAndHandlesNoMatchCommitAck() {
        ready(); assertFalse(client.finalizeTurn())
        client.sendAudioChunk(byteArrayOf(1, 0)); assertTrue(client.finalizeTurn()); pump()
        assertFalse(client.finalizeTurn())
        session.final("", end = 0); pump()
        assertFalse(client.hasPendingProcessing)
        assertFalse(events.any { it.startsWith("text:") })
        assertEquals(1, session.commits.size)
    }
    @Test fun suppressesDuplicateResultIdsButKeepsRepeatedPhrases() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finalizeTurn(); pump()
        session.final("Again.", "first"); session.final("Again.", "first"); pump()
        client.sendAudioChunk(byteArrayOf(2, 0)); client.finalizeTurn(); pump()
        session.final("Again.", "second"); pump()
        assertEquals(listOf("text:Again.", "text:Again."), events.filter { it.startsWith("text:") })
    }
    @Test fun finalBeforeCommitCallbackStillSettlesByCorrelationToken() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finalizeTurn(); pump()
        session.listener.onFinal("first", "Complete.", 0, 7)
        session.listener.onCommitRequested(7, 2); pump()
        assertFalse(client.hasPendingProcessing)
    }
    @Test fun advisoryCommitNeedNotBeEchoedWhenFinalCoversAudio() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finalizeTurn(); pump()
        session.final("Complete.", token = 0); pump()
        assertFalse(client.hasPendingProcessing)
    }
    @Test fun unacknowledgedSilenceBoundaryDrainsWithoutFailingOrDiscardingLaterFinals() {
        ready(); client.sendAudioChunk(ByteArray(3200)); client.finalizeTurn(); pump()
        // A final can stop before the requested boundary and omit the advisory token.
        session.final("First phrase.", "first", end = 1600, token = 0); pump()
        client.sendAudioChunk(ByteArray(3200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertTrue(session.inputFinished); assertFalse(session.closed)
        assertFalse(events.any { it.startsWith("error:") })
        session.final("Remaining words.", "second", token = 0); session.ended(); pump()
        assertEquals(listOf("text:First phrase.", "text:Remaining words."), events.filter { it.startsWith("text:") })
        assertFalse(client.hasPendingProcessing); assertEquals("closed", events.last())
    }
    @Test fun rejectedCommitAndNoMatchWithoutOffsetsUseEofInsteadOfAssumingCompletion() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finalizeTurn(); pump()
        session.listener.onCommitRequested(0, 2)
        session.final("", end = 0, token = 0); pump()
        assertTrue(client.hasPendingProcessing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertTrue(session.inputFinished)
        session.ended(); pump()
        assertFalse(events.any { it.startsWith("error:") || it.startsWith("text:") })
        assertFalse(client.hasPendingProcessing)
    }
    @Test fun pendingCommitLimitRequestsDrainWithoutLosingUploadedSpeech() {
        ready()
        repeat(64) { client.sendAudioChunk(byteArrayOf(1, 0)); assertTrue(client.finalizeTurn()); pump() }
        client.sendAudioChunk(byteArrayOf(1, 0)); assertFalse(client.finalizeTurn())
        assertTrue(session.inputFinished); assertFalse(session.closed)
        session.final("All buffered speech.", token = 0); session.ended(); pump()
        assertTrue("text:All buffered speech." in events)
        assertFalse(events.any { it.startsWith("error:") })
    }
    @Test fun cancellationDiscardsQueuedCallbacksIncludingEof() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finishStreaming()
        session.final("Discard this."); session.ended(); client.cancelAll(); pump()
        assertFalse(events.any { it.startsWith("text:") || it == "closed" })
        assertFalse(client.hasPendingProcessing)
    }
    @Test fun brokenSessionNeverReplaysUploadedUnconfirmedSpeech() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0))
        session.listener.onError("Connection failed."); pump()
        assertTrue(events.any { it.startsWith("error:true:") && it.contains("could not be finalized") })
        assertFalse(client.hasPendingProcessing)
    }
    @Test fun initialConnectionFailureReportsNoUnfinalizedAudio() {
        session.listener.onError("Connection failed."); pump()
        assertTrue(events.any { it.startsWith("error:false:") })
    }
    @Test fun unexpectedSessionStopReportsUnfinalizedAudio() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); session.ended(); pump()
        assertTrue(events.any { it.startsWith("error:true:") })
        assertFalse("closed" in events)
    }
    @Test fun eofTimeoutDoesNotPromoteAnyProvisionalText() {
        ready(); client.sendAudioChunk(byteArrayOf(1, 0)); client.finishStreaming()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertTrue(events.any { it.startsWith("error:true:") && it.contains("draining") })
        assertFalse(events.any { it.startsWith("text:") })
    }
    @Test fun invalidPcmOrBackpressureStopsWithExplicitError() {
        ready(); session.acceptWrites = false
        assertFalse(client.sendAudioChunk(byteArrayOf(1, 0)))
        assertTrue(events.any { it.contains("could not accept") })
    }
    @Test fun unsupportedPlatformDoesNotCreateNativeSession() {
        client.cancelAll()
        var created = false
        client = MaiTranscriptionClient({ created = true; session }, platformError = { "Unsupported device." })
        client.startStreaming(MaiConfig("key", "centralus", true), null, callback())
        assertFalse(created)
        assertTrue(events.any { it.contains("Unsupported device.") })
    }
    @Test fun unfinalizedAudioDeadlineCannotBeExtendedByNewAudioOrCommitCallbacks() {
        client.cancelAll(); events.clear(); session = FakeMaiSpeechSession()
        client = MaiTranscriptionClient({ session }, 60_000L, { null }, progressTimeoutMs = 1_000L)
        client.startStreaming(MaiConfig("key", "centralus", true), null, callback()); ready()
        client.sendAudioChunk(ByteArray(3200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        client.sendAudioChunk(ByteArray(3200)); client.finalizeTurn(); pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(session.inputFinished); assertFalse(session.closed)
        assertTrue("stalled" in events); assertFalse(events.any { it.startsWith("error:") })
        session.final("Accepted tail."); session.ended(); pump()
        assertTrue("text:Accepted tail." in events); assertEquals("closed", events.last())
    }
    @Test fun finalProgressSettlesOldestDeadlineAndNoMatchDoesNotInsertAGap() {
        client.cancelAll(); events.clear(); session = FakeMaiSpeechSession()
        client = MaiTranscriptionClient({ session }, 60_000L, { null }, progressTimeoutMs = 1_000L)
        client.startStreaming(MaiConfig("key", "centralus", true), null, callback()); ready()
        client.sendAudioChunk(ByteArray(3200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        session.final("", token = 0); pump()
        client.sendAudioChunk(ByteArray(3200))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertFalse(events.any { it.startsWith("error:") })
        session.final("After silence.", id = "second", token = 0); pump()
        assertTrue("text:After silence." in events)
    }
    @Test fun backpressureIsNotAnAcknowledgmentOrFailureAndRetriedChunkIsCountedOnce() {
        ready(); session.backpressure = true
        assertFalse(client.sendAudioChunk(ByteArray(3200))); assertFalse(client.hasPendingProcessing)
        assertFalse(events.any { it.startsWith("error:") })
        session.backpressure = false; session.listener.onWriteAvailable(); pump()
        assertTrue("capacity" in events)
        assertTrue(client.sendAudioChunk(ByteArray(3200))); assertEquals(3200, session.audio.single().size)
    }
    @Test fun inconsistentFinalTimingStopsBeforeAppendingOutOfOrderText() {
        ready(); client.sendAudioChunk(ByteArray(3200)); session.final("First.", end = 1600, token = 0); pump()
        session.final("Old suffix.", id = "older", end = 800, token = 0); pump()
        assertEquals(listOf("text:First."), events.filter { it.startsWith("text:") })
        assertTrue(events.any { it.contains("inconsistent audio timing") })
    }

}
