// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log

internal interface VoiceNetworkMonitor {
    /** Preflight and terminal-loss observation. Callback runs on the main looper. */
    fun start(onUnavailable: () -> Unit): Boolean
    fun stop()
}

/** Observes the default route, not merely the existence of any connected Wi-Fi. */
internal class AndroidVoiceNetworkMonitor(private val context: Context) : VoiceNetworkMonitor {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null

    override fun start(onUnavailable: () -> Unit): Boolean {
        stop()
        val connectivity = manager ?: return false
        val token = generation
        try {
            val available = hasInternet()
            Log.i("VoiceNetwork", "preflight usable=$available sdk=${Build.VERSION.SDK_INT}")
            if (!available) return false
            if (Build.VERSION.SDK_INT >= 24) {
                var currentNetwork = connectivity.activeNetwork
                val observer = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        handler.post { if (generation == token) currentNetwork = network }
                    }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        handler.post {
                            if (generation == token && currentNetwork == network && !usable(capabilities)) {
                                Log.w("VoiceNetwork", "default route lost usable capabilities " +
                                    "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                                    "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                                onUnavailable()
                            }
                        }
                    }
                    override fun onLost(network: Network) {
                        handler.post {
                            if (generation == token && currentNetwork == network) {
                                Log.w("VoiceNetwork", "default route lost")
                                onUnavailable()
                            }
                        }
                    }
                }
                callback = observer
                connectivity.registerDefaultNetworkCallback(observer)
            } else {
                // The app supports API 21; default-network callbacks start at 24.
                // A dynamically registered legacy broadcast covers older devices.
                val observer = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (generation == token && !hasInternet()) {
                            Log.w("VoiceNetwork", "legacy connectivity broadcast: no usable route")
                            onUnavailable()
                        }
                    }
                }
                receiver = observer
                @Suppress("DEPRECATION")
                context.registerReceiver(observer, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            }
            if (!hasInternet()) { stop(); return false }
            return true
        } catch (e: RuntimeException) {
            Log.w("VoiceNetwork", "network observation failed: ${e.javaClass.simpleName}")
            stop()
            return false
        }
    }

    override fun stop() {
        generation += 1 // Already posted observations cannot kill a new recording.
        callback?.let { try { manager?.unregisterNetworkCallback(it) } catch (_: RuntimeException) { } }
        callback = null
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: RuntimeException) { } }
        receiver = null
    }

    private fun hasInternet(): Boolean {
        val connectivity = manager ?: return false
        return if (Build.VERSION.SDK_INT >= 23) {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let(::usable) == true
        } else {
            // API 21–22 do not expose default-route validation. Socket failures
            // and bounded progress deadlines remain authoritative there.
            @Suppress("DEPRECATION")
            (connectivity.activeNetworkInfo?.isConnected == true)
        }
    }

    private fun usable(capabilities: NetworkCapabilities) =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
