// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.TranscriptionPreferences
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceMicrophoneRouterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = Mockito.mock(AudioManager::class.java)
    private val recorder = Mockito.mock(AudioRecord::class.java)
    private val phone = device(AudioDeviceInfo.TYPE_BUILTIN_MIC)
    private val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private var time = 0L

    private fun device(type: Int): AudioDeviceInfo = Mockito.mock(AudioDeviceInfo::class.java).also {
        Mockito.`when`(it.type).thenReturn(type)
    }

    private fun router(builtIn: Boolean = false) = VoiceMicrophoneRouter(
        context, manager,
        if (builtIn) TranscriptionPreferences.MICROPHONE_BUILT_IN else TranscriptionPreferences.MICROPHONE_AUTOMATIC,
    ) { time }

    private fun inputs(vararg devices: AudioDeviceInfo) {
        Mockito.`when`(manager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(arrayOf(*devices))
        devices.forEach { Mockito.`when`(recorder.setPreferredDevice(it)).thenReturn(true) }
    }

    @Test fun builtInBypassesBluetoothWiredAndUsbAndVerifiesActualRoute() {
        inputs(bluetooth, device(AudioDeviceInfo.TYPE_USB_HEADSET), device(AudioDeviceInfo.TYPE_WIRED_HEADSET), phone)
        val route = router(builtIn = true)
        route.prepare(recorder)
        Mockito.verify(recorder).setPreferredDevice(phone)
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertTrue(route.acceptsAudio(recorder))
        Mockito.verify(manager).getDevices(AudioManager.GET_DEVICES_INPUTS)
        Mockito.verifyNoMoreInteractions(manager)
    }

    @Test fun ignoredPhonePreferenceNeverAcceptsHeadsetAudioAndTimesOut() {
        inputs(bluetooth, phone)
        val route = router(builtIn = true)
        route.prepare(recorder)
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertFalse(route.acceptsAudio(recorder))
        time = VoiceMicrophoneRouter.ROUTE_TIMEOUT_MS
        assertFailsWith<IllegalStateException> { route.acceptsAudio(recorder) }
    }

    @Test fun phoneRouteCanBecomeReadyButCannotSilentlySwitchToExternal() {
        inputs(phone, bluetooth)
        val route = router(builtIn = true)
        route.prepare(recorder)
        assertFalse(route.acceptsAudio(recorder)) // Null is unknown while capture starts.
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertTrue(route.acceptsAudio(recorder))
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertFailsWith<IllegalStateException> { route.acceptsAudio(recorder) }
    }

    @Test fun automaticRequestsBluetoothOutputAndInputAndReleasesOnlyOnce() {
        inputs(phone, bluetooth)
        val output = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(output))
        Mockito.`when`(manager.setCommunicationDevice(output)).thenReturn(true)
        val route = router()
        route.prepare(recorder)
        assertTrue(route.usesBluetooth)
        assertEquals(16_000, route.captureSampleRate)
        Mockito.verify(manager).setCommunicationDevice(output)
        Mockito.verify(recorder).setPreferredDevice(bluetooth)
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertFalse(route.acceptsAudio(recorder))
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
        route.release()
        Mockito.verify(manager, Mockito.times(1)).clearCommunicationDevice()
    }

    @Test fun automaticSupportsBleHeadsetWithoutTreatingA2dpPlaybackAsAMicrophone() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET)
        inputs(phone, bluetooth, ble)
        val output = device(AudioDeviceInfo.TYPE_BLE_HEADSET)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(output))
        Mockito.`when`(manager.setCommunicationDevice(output)).thenReturn(true)
        val route = router()
        route.prepare(recorder)
        Mockito.verify(recorder).setPreferredDevice(ble)
        Mockito.`when`(recorder.routedDevice).thenReturn(ble)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
    }

    @Test fun bluetoothCanActivateBeforeItsInputAppearsInTheInputDeviceList() {
        inputs(phone)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(bluetooth))
        Mockito.`when`(manager.setCommunicationDevice(bluetooth)).thenReturn(true)
        val route = router()
        route.prepare(recorder)
        assertTrue(route.usesBluetooth)
        Mockito.verify(manager).setCommunicationDevice(bluetooth)
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertFalse(route.acceptsAudio(recorder))
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
    }

    @Test fun automaticWithoutBluetoothKeepsPlatformRoutingAndClearsPreviousPreference() {
        inputs(phone)
        Mockito.`when`(recorder.preferredDevice).thenReturn(bluetooth)
        Mockito.`when`(recorder.setPreferredDevice(null)).thenReturn(true)
        val route = router()
        route.prepare(recorder)
        Mockito.verify(recorder).setPreferredDevice(null)
        assertFalse(route.usesBluetooth)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
        Mockito.verify(manager, Mockito.never()).clearCommunicationDevice()
    }

    @Test fun failedInputSelectionStillReleasesTheOwnedBluetoothRequest() {
        inputs(bluetooth)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(bluetooth))
        Mockito.`when`(manager.setCommunicationDevice(bluetooth)).thenReturn(true)
        Mockito.`when`(recorder.setPreferredDevice(bluetooth)).thenReturn(false)
        val route = router()
        assertFailsWith<IllegalStateException> { route.prepare(recorder) }
        route.release()
        Mockito.verify(manager).clearCommunicationDevice()
    }

    @Test fun missingBuiltInDeviceFailsInsteadOfFallingBackToHeadset() {
        inputs(bluetooth)
        assertFailsWith<IllegalStateException> { router(builtIn = true).prepare(recorder) }
        Mockito.verifyNoInteractions(recorder)
    }

    @Test fun rejectedBluetoothSelectionFailsWithoutClearingSomeOtherRequest() {
        inputs(bluetooth)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(bluetooth))
        val route = router()
        assertFailsWith<IllegalStateException> { route.prepare(recorder) }
        route.release()
        Mockito.verify(manager, Mockito.never()).clearCommunicationDevice()
    }

    @Suppress("DEPRECATION")
    @Test @Config(sdk = [23, 30]) fun olderAndroidRequestsScoAndCleansUpWithoutModernApis() {
        inputs(bluetooth, phone)
        val route = router()
        assertEquals(8_000, route.captureSampleRate)
        route.prepare(recorder)
        Mockito.verify(manager).startBluetoothSco()
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
        route.release()
        Mockito.verify(manager, Mockito.times(1)).stopBluetoothSco()
        Mockito.verify(manager).getDevices(AudioManager.GET_DEVICES_INPUTS)
        Mockito.verifyNoMoreInteractions(manager)
    }

    @Test @Config(sdk = [21, 22]) fun preMarshmallowAutomaticUsesNoUnavailableDeviceApis() {
        val route = router()
        route.prepare(recorder)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
        Mockito.verifyNoInteractions(manager, recorder)
        assertFailsWith<IllegalStateException> { router(builtIn = true).prepare(recorder) }
        assertFailsWith<IllegalStateException> { bluetoothOnly().prepare(recorder) }
    }

    private fun bluetoothOnly() = VoiceMicrophoneRouter(context, manager, TranscriptionPreferences.MICROPHONE_BLUETOOTH) { time }

    @Test fun bluetoothOnlyRejectsPhoneWiredUsbAndPlaybackOnlyDevicesBeforeRecording() {
        inputs(phone, device(AudioDeviceInfo.TYPE_WIRED_HEADSET), device(AudioDeviceInfo.TYPE_USB_HEADSET))
        val playbackOnly = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(playbackOnly))
        assertFailsWith<IllegalStateException> { bluetoothOnly().prepare(recorder) }
        Mockito.verifyNoInteractions(recorder)
        Mockito.verify(manager, Mockito.never()).clearCommunicationDevice()
    }

    @Test fun bluetoothOnlyVerifiesRouteAndFailsOnDisconnectInsteadOfFallingBack() {
        inputs(phone)
        Mockito.`when`(manager.availableCommunicationDevices).thenReturn(listOf(bluetooth))
        Mockito.`when`(manager.setCommunicationDevice(bluetooth)).thenReturn(true)
        val route = bluetoothOnly()
        route.prepare(recorder)
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertFalse(route.acceptsAudio(recorder))
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertTrue(route.acceptsAudio(recorder))
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertFailsWith<IllegalStateException> { route.acceptsAudio(recorder) }
        route.release()
        Mockito.verify(manager).clearCommunicationDevice()
    }

    @Suppress("DEPRECATION")
    @Test @Config(sdk = [23, 30]) fun legacyBluetoothCanActivateBeforeItsInputIsPublished() {
        inputs(phone)
        Mockito.`when`(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(bluetooth))
        val route = bluetoothOnly()
        assertEquals(8_000, route.captureSampleRate)
        route.prepare(recorder)
        Mockito.verify(manager).startBluetoothSco()
        Mockito.`when`(recorder.routedDevice).thenReturn(phone)
        assertFalse(route.acceptsAudio(recorder))
        Mockito.`when`(recorder.routedDevice).thenReturn(bluetooth)
        assertTrue(route.acceptsAudio(recorder))
        route.release()
        Mockito.verify(manager).stopBluetoothSco()
    }

    @Test @Config(sdk = [23, 30]) fun legacySampleRateDependsOnSelectedPolicyAndAvailableHeadset() {
        inputs(phone, bluetooth)
        assertEquals(8_000, bluetoothOnly().captureSampleRate)
        assertEquals(16_000, router(builtIn = true).captureSampleRate)
        inputs(phone)
        assertEquals(16_000, router().captureSampleRate)
        assertFailsWith<IllegalStateException> { bluetoothOnly().captureSampleRate }
    }
}
