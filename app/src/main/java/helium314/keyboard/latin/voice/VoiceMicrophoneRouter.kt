// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.SystemClock
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.Log

/** One capture phase owns its routing request; never persists transient Android device IDs. */
internal class VoiceMicrophoneRouter(
    private val context: Context,
    private val audioManager: AudioManager,
    private val preference: String,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var communicationRequested = false
    private var scoRequested = false
    private var requiredType: Int? = null
    private var ready = false
    private var deadline = 0L

    val usesBluetooth: Boolean get() = requiredType?.let(::isBluetooth) == true

    /** Called before starting AudioRecord. API 21–22 retain platform routing in automatic mode. */
    @Suppress("DEPRECATION")
    fun prepare(recorder: AudioRecord) {
        deadline = now() + ROUTE_TIMEOUT_MS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            check(preference != TranscriptionPreferences.MICROPHONE_BUILT_IN) {
                context.getString(R.string.voice_microphone_unsupported)
            }
            return
        }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtIn = preference == TranscriptionPreferences.MICROPHONE_BUILT_IN
        // Some phones expose a headset's input only after its communication route activates.
        // On Android 12+, discover the Bluetooth OUTPUT first rather than requiring a live input.
        val bluetoothOutput = if (!builtIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val outputs = audioManager.availableCommunicationDevices
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else null
        val input = if (builtIn) {
            inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: error(context.getString(R.string.voice_microphone_builtin_unavailable))
        } else if (bluetoothOutput != null) {
            inputs.firstOrNull { it.type == bluetoothOutput.type }
        } else {
            inputs.firstOrNull { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
        requiredType = bluetoothOutput?.type ?: input?.type
        // No Bluetooth input: leave wired/USB/phone selection to Android.
        if (requiredType == null) {
            // A resumed AudioRecord may still prefer the previous phase's phone/headset input.
            if (recorder.preferredDevice != null) {
                check(recorder.setPreferredDevice(null)) {
                    context.getString(R.string.voice_microphone_selection_failed)
                }
            }
            return
        }

        if (usesBluetooth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // This API accepts OUTPUT devices; Android selects the matching input.
                val output = bluetoothOutput
                    ?: error(context.getString(R.string.voice_microphone_bluetooth_unavailable))
                check(audioManager.setCommunicationDevice(output)) {
                    context.getString(R.string.voice_microphone_bluetooth_unavailable)
                }
                communicationRequested = true
            } else {
                scoRequested = true
                audioManager.startBluetoothSco()
            }
        }
        // With no published input yet, the communication request selects the matching source.
        // Clear a previous phase's preference so it cannot pin us to the phone while activating.
        if (input != null || recorder.preferredDevice != null) {
            check(recorder.setPreferredDevice(input)) {
                context.getString(R.string.voice_microphone_selection_failed)
            }
        }
        Log.i(TAG, "VOICE microphone preference=$preference requestedType=$requiredType")
    }

    /** Verify the actual route before exposing PCM; a preferred device is only a request. */
    fun acceptsAudio(recorder: AudioRecord): Boolean {
        val expected = requiredType ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val actual = recorder.routedDevice?.type
        if (actual == expected) {
            if (!ready) Log.i(TAG, "VOICE microphone ready type=$actual")
            ready = true
            return true
        }
        check(!ready && now() < deadline) {
            context.getString(if (ready) R.string.voice_microphone_route_lost else R.string.voice_microphone_route_timeout)
        }
        return false
    }

    @Suppress("DEPRECATION")
    fun release() {
        // Mark ownership released first so cleanup remains idempotent even if Android throws.
        val clearCommunication = communicationRequested
        val stopSco = scoRequested
        communicationRequested = false
        scoRequested = false
        try {
            if (clearCommunication && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            if (stopSco) audioManager.stopBluetoothSco()
        } catch (_: Exception) {
            Log.w(TAG, "Unable to release microphone routing request")
        }
    }

    private fun isBluetooth(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    companion object {
        private const val TAG = "VoiceMicrophoneRouter"
        internal const val ROUTE_TIMEOUT_MS = 8_000L
    }
}
