// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.media.AudioRecord
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VoiceRecorderLifecycleTest {
    @Test fun missedCaptureJoinReportsFailureAndBlocksReuseOfTheLiveThread() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val events = Events()
        recorder.setCallback(events)
        val release = CountDownLatch(1)
        val worker = Thread { release.await() }.apply { start() }
        setField(recorder, "isRecording", true)
        setField(recorder, "recordingThread", worker)
        try {
            recorder.stopRecording()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(recorder.isCurrentlyRecording)
            assertEquals(listOf("Microphone capture did not stop"), events.errors)
            assertFalse(recorder.startRecording())
            assertEquals("Previous microphone capture has not stopped", events.errors.last())
            assertTrue(worker.isAlive)
        } finally {
            release.countDown()
            worker.join(1000)
        }
    }

    @Test fun aReadAlreadyInFlightKeepsTheOriginalRecordingCallbackAfterReplacement() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val original = Events()
        val replacement = Events()
        recorder.setCallback(original)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val audio = Mockito.mock(AudioRecord::class.java) { call ->
            if (call.method.name == "read") {
                if (reads++ == 0) {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    3200
                } else AudioRecord.ERROR_INVALID_OPERATION
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        setField(recorder, "isRecording", true)
        setField(recorder, "audioRecord", audio)
        val loop = VoiceRecorder::class.java.getDeclaredMethod("recordingLoop", VoiceRecorder.RecordingCallback::class.java)
            .apply { isAccessible = true }
        val worker = Thread { loop.invoke(recorder, original) }
        setField(recorder, "recordingThread", worker)
        worker.start()
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            recorder.setCallback(replacement)
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, original.audioChunks)
            assertEquals(1, original.errors.size)
            assertEquals(0, replacement.audioChunks)
            assertTrue(replacement.errors.isEmpty())
        } finally {
            release.countDown()
            recorder.stopRecording()
            worker.join(1000)
        }
    }

    private fun setField(recorder: VoiceRecorder, name: String, value: Any) {
        VoiceRecorder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(recorder, value)
    }

    private class Events : VoiceRecorder.RecordingCallback {
        var audioChunks = 0
        val errors = mutableListOf<String>()
        override fun onRecordingStarted() { }
        override fun onAudioChunk(pcmData: ByteArray) { audioChunks++ }
        override fun onSpeechStarted() { }
        override fun onSpeechStopped() { }
        override fun onRecordingStopped() { }
        override fun onRecordingError(error: String) { errors.add(error) }
    }
}
