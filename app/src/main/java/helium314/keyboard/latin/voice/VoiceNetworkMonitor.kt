// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

internal interface VoiceNetworkMonitor {
    /** False means capture must not start. Callbacks run on the main looper. */
    fun start(onUnavailable: () -> Unit): Boolean
    fun stop()
}

/** Android validation is a preflight signal; SDK events also check the actual service. */
internal class AndroidVoiceNetworkMonitor(context: Context) : VoiceNetworkMonitor {
    // The IME also constructs this on API 21. Only start(), after the SDK's
    // API-26 support gate, may use the newer network observation methods.
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onUnavailable: () -> Unit): Boolean {
        stop()
        val manager = connectivity ?: return false
        fun validated(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)?.let(::usable) == true
        try {
            if (!validated()) return false
            var current = manager.activeNetwork
            val observer = object : ConnectivityManager.NetworkCallback() {
                private fun active() = callback === this
                override fun onAvailable(network: Network) { if (active()) current = network }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (active() && current == network && !usable(capabilities)) onUnavailable()
                }
                override fun onLost(network: Network) {
                    if (active() && current == network) onUnavailable()
                }
            }
            callback = observer
            manager.registerDefaultNetworkCallback(observer, handler)
            if (!validated()) { stop(); return false }
            return true
        } catch (_: RuntimeException) { stop(); return false }
    }

    override fun stop() {
        val observer = callback ?: return
        callback = null // Invalidate already queued callbacks before unregistering.
        try { connectivity?.unregisterNetworkCallback(observer) } catch (_: RuntimeException) { }
    }

    private fun usable(capabilities: NetworkCapabilities) =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
