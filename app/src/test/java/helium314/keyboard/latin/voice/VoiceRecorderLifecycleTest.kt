// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.prefs
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VoiceRecorderLifecycleTest {
    @Test fun legacyCaptureReadsOneHundredMillisecondsAndUploadsQuietPcmAtSixteenKhz() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val events = Events()
        recorder.setCallback(events)
        var reads = 0
        val audio = Mockito.mock(AudioRecord::class.java) { call ->
            if (call.method.name == "read") {
                assertEquals(1600, call.arguments[2]) // 100 ms at 8 kHz PCM16.
                if (++reads == 2) {
                    setField(recorder, "isRecording", false)
                    0
                } else {
                    val data = call.arguments[0] as ByteArray
                    for (offset in 0 until 1600 step 2) { data[offset] = 50; data[offset + 1] = 0 }
                    1600
                }
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        setField(recorder, "isRecording", true)
        setField(recorder, "audioRecord", audio)
        setField(recorder, "captureSampleRate", 8000)
        VoiceRecorder::class.java.getDeclaredMethod("recordingLoop", VoiceRecorder.RecordingCallback::class.java)
            .apply { isAccessible = true }.invoke(recorder, events)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.audioChunks)
        assertEquals(3200, events.audioBytes)
        assertEquals(50, events.lastSample)
        assertEquals(listOf(false), events.speechEvidence)
        assertTrue(events.errors.isEmpty())
    }

    @Test fun pauseAndFailedResumeReleaseTheirOwnBluetoothRequests() {
        val context = Mockito.spy(ApplicationProvider.getApplicationContext<Context>())
        TranscriptionPreferences.writeMicrophone(context.prefs(), TranscriptionPreferences.MICROPHONE_AUTOMATIC)
        val manager = Mockito.mock(AudioManager::class.java)
        Mockito.doReturn(manager).`when`(context).getSystemService(Context.AUDIO_SERVICE)
        val headset = Mockito.mock(AudioDeviceInfo::class.java)
        Mockito.`when`(headset.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        Mockito.`when`(manager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(arrayOf(headset))
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(headset))
        Mockito.`when`(manager.setCommunicationDevice(headset)).thenReturn(true)
        val audio = Mockito.mock(AudioRecord::class.java)
        Mockito.`when`(audio.setPreferredDevice(headset)).thenReturn(true)
        Mockito.doThrow(IllegalStateException("capture unavailable")).`when`(audio).startRecording()
        val router = VoiceMicrophoneRouter(context, manager, TranscriptionPreferences.MICROPHONE_AUTOMATIC)
        router.prepare(audio)
        val recorder = VoiceRecorder(context)
        val events = Events()
        recorder.setCallback(events)
        setField(recorder, "audioRecord", audio)
        setField(recorder, "microphoneRouter", router)
        setField(recorder, "isRecording", true)
        try {
            recorder.pauseRecording()
            Mockito.verify(manager, Mockito.times(1)).clearCommunicationDevice()
            recorder.resumeRecording()
            shadowOf(Looper.getMainLooper()).idle()
            Mockito.verify(manager, Mockito.times(2)).setCommunicationDevice(headset)
            Mockito.verify(manager, Mockito.times(2)).clearCommunicationDevice()
            assertTrue(recorder.isCurrentlyPaused)
            assertEquals(listOf("Failed to resume recording: capture unavailable"), events.errors)
        } finally { recorder.stopRecording() }
        Mockito.verify(manager, Mockito.times(2)).clearCommunicationDevice()
    }

    @Test fun selectedPhoneRouteWithholdsHeadsetPcmThenFailsOnExternalRouteChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = VoiceRecorder(context)
        val events = Events()
        recorder.setCallback(events)
        val phone = Mockito.mock(AudioDeviceInfo::class.java)
        val headset = Mockito.mock(AudioDeviceInfo::class.java)
        Mockito.`when`(phone.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        Mockito.`when`(headset.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val manager = Mockito.mock(AudioManager::class.java)
        Mockito.`when`(manager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(arrayOf(headset, phone))
        var reads = 0
        val audio = Mockito.mock(AudioRecord::class.java) { call ->
            when (call.method.name) {
                "setPreferredDevice" -> true
                "getRoutedDevice" -> if (reads == 2) phone else headset
                "read" -> { reads++; (call.arguments[0] as ByteArray).fill(reads.toByte()); 3200 }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }
        val router = VoiceMicrophoneRouter(context, manager, TranscriptionPreferences.MICROPHONE_BUILT_IN)
        router.prepare(audio)
        setField(recorder, "isRecording", true)
        setField(recorder, "audioRecord", audio)
        setField(recorder, "microphoneRouter", router)
        try {
            VoiceRecorder::class.java.getDeclaredMethod("recordingLoop", VoiceRecorder.RecordingCallback::class.java)
                .apply { isAccessible = true }.invoke(recorder, events)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, reads)
            assertEquals(1, events.audioChunks)
            assertEquals(1, events.errors.size)
            assertTrue(events.errors.single().contains("microphone changed or disconnected"))
        } finally { recorder.stopRecording() }
    }

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

    @Test fun aNativeReadErrorFromBeforeRapidPauseResumeDoesNotAbortResumedCapture() {
        verifyInterruptedReadAfterResume(throws = false)
    }

    @Test fun aNativeReadExceptionFromBeforeRapidPauseResumeDoesNotAbortResumedCapture() {
        verifyInterruptedReadAfterResume(throws = true)
    }

    private fun verifyInterruptedReadAfterResume(throws: Boolean) {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val events = Events()
        recorder.setCallback(events)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val audio = Mockito.mock(AudioRecord::class.java) { call ->
            if (call.method.name == "read") {
                when (reads++) {
                    0 -> {
                        reading.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        if (throws) throw IllegalStateException("interrupted by pause")
                        AudioRecord.ERROR_INVALID_OPERATION
                    }
                    1 -> { (call.arguments[0] as ByteArray).fill(0); 3200 }
                    else -> AudioRecord.ERROR_BAD_VALUE // A real new-phase error must still surface.
                }
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        setField(recorder, "isRecording", true)
        setField(recorder, "audioRecord", audio)
        val loop = VoiceRecorder::class.java.getDeclaredMethod("recordingLoop", VoiceRecorder.RecordingCallback::class.java)
            .apply { isAccessible = true }
        val worker = Thread { loop.invoke(recorder, events) }
        setField(recorder, "recordingThread", worker)
        worker.start()
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            recorder.pauseRecording()
            recorder.resumeRecording()
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, reads)
            assertEquals(1, events.audioChunks)
            assertEquals(listOf("Microphone returned invalid audio buffer"), events.errors)
        } finally {
            release.countDown()
            recorder.stopRecording()
            worker.join(1000)
        }
    }

    @Test fun quietChunkDuringTheSpeakingWindowCarriesNoSpeechEvidence() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val events = Events()
        var reads = 0
        val audio = Mockito.mock(AudioRecord::class.java) { call ->
            if (call.method.name == "read") {
                val buffer = call.arguments[0] as ByteArray
                when (reads++) {
                    0 -> { // Loud PCM starts speech; the next quiet frame is still in the silence window.
                        buffer.indices.forEach { buffer[it] = if (it % 2 == 0) 0xd0.toByte() else 7 }
                        buffer.size
                    }
                    1 -> { buffer.fill(0); buffer.size }
                    else -> AudioRecord.ERROR_INVALID_OPERATION
                }
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        setField(recorder, "isRecording", true)
        setField(recorder, "audioRecord", audio)
        val loop = VoiceRecorder::class.java.getDeclaredMethod("recordingLoop", VoiceRecorder.RecordingCallback::class.java)
            .apply { isAccessible = true }
        val worker = Thread { loop.invoke(recorder, events) }
        setField(recorder, "recordingThread", worker)
        worker.start()
        try {
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertTrue(recorder.isCurrentlySpeaking)
            // Main-looper delivery must keep its capture-time annotation even if state changed.
            setField(recorder, "isSpeaking", false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false), events.speechEvidence)
            assertEquals(2, events.audioChunks)
        } finally { recorder.stopRecording(); worker.join(1000) }
    }

    @Test fun sustainedQuietPcmStillReachesTheCallbackAfterLocalSpeechStops() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        recorder.updateSilenceConfig(1000, 50.0)
        val events = Events()
        var reads = 0
        val audio = Mockito.mock(AudioRecord::class.java) { call ->
            if (call.method.name == "read") {
                val buffer = call.arguments[0] as ByteArray
                if (reads++ < 40) {
                    val sample = if (reads <= 5) 2000 else 80
                    for (i in buffer.indices step 2) {
                        buffer[i] = sample.toByte()
                        buffer[i + 1] = (sample shr 8).toByte()
                    }
                    buffer.size
                } else AudioRecord.ERROR_INVALID_OPERATION
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        setField(recorder, "isRecording", true)
        setField(recorder, "audioRecord", audio)
        val loop = VoiceRecorder::class.java.getDeclaredMethod("recordingLoop", VoiceRecorder.RecordingCallback::class.java)
            .apply { isAccessible = true }
        // Run synchronously: this fake never blocks on a native read.
        loop.invoke(recorder, events)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(40, events.audioChunks)
        assertEquals(35, events.speechEvidence.count { !it })
        assertEquals(1, events.speechStops)
        assertFalse(recorder.isCurrentlySpeaking)
        assertEquals(80, events.lastSample)
        recorder.stopRecording()
    }

    @Test @Config(sdk = [35])
    fun captureRouteReportsActualBluetoothTypeWithoutDeviceIdentity() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val device = Mockito.mock(AudioDeviceInfo::class.java)
        Mockito.`when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        Mockito.`when`(device.productName).thenReturn("Private earbuds")
        val audio = Mockito.mock(AudioRecord::class.java)
        Mockito.`when`(audio.routedDevice).thenReturn(device)
        setField(recorder, "audioRecord", audio)
        assertEquals("source=MIC route=bluetooth_sco", captureRoute(recorder))
        Mockito.`when`(audio.routedDevice).thenThrow(IllegalStateException("Private error"))
        assertEquals("source=MIC route=query_unavailable", captureRoute(recorder))
    }

    @Test @Config(sdk = [21], application = Application::class)
    fun oldAndroidDoesNotQueryUnavailableRoutingApi() {
        val recorder = VoiceRecorder(ApplicationProvider.getApplicationContext<Context>())
        val audio = Mockito.mock(AudioRecord::class.java)
        setField(recorder, "audioRecord", audio)
        assertEquals("source=MIC route=unavailable_api", captureRoute(recorder))
        Mockito.verifyNoInteractions(audio)
    }

    private fun captureRoute(recorder: VoiceRecorder): String =
        VoiceRecorder::class.java.getDeclaredMethod("captureRouteDiagnostic")
            .apply { isAccessible = true }.invoke(recorder) as String

    private class Events : VoiceRecorder.RecordingCallback {
        var audioChunks = 0
        var audioBytes = 0
        val speechEvidence = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        var speechStops = 0
        var lastSample = 0
        override fun onRecordingStarted() { }
        override fun onAudioChunk(pcmData: ByteArray, containsSpeech: Boolean) {
            audioChunks++
            audioBytes += pcmData.size
            speechEvidence.add(containsSpeech)
            lastSample = (pcmData[1].toInt() shl 8) or (pcmData[0].toInt() and 0xff)
        }
        override fun onSpeechStarted() { }
        override fun onSpeechStopped() { speechStops++ }
        override fun onRecordingStopped() { }
        override fun onRecordingError(error: String) { errors.add(error) }
    }
}
