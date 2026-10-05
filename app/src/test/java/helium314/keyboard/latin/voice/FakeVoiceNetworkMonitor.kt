// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

internal class FakeVoiceNetworkMonitor : VoiceNetworkMonitor {
    var available = true
    var unavailable: (() -> Unit)? = null
    override fun start(onUnavailable: () -> Unit): Boolean {
        unavailable = if (available) onUnavailable else null
        return available
    }
    override fun stop() { unavailable = null }
    fun loseConnection() { available = false; unavailable?.invoke() }
}
