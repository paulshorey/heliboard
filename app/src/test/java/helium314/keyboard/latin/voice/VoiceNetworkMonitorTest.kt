// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceNetworkMonitorTest {
    @Test fun rejectsUnvalidatedNetworkAndUnregistersOnStop() {
        val context = Mockito.mock(Context::class.java)
        val manager = Mockito.mock(ConnectivityManager::class.java)
        val network = Mockito.mock(Network::class.java)
        Mockito.`when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager)
        Mockito.`when`(manager.activeNetwork).thenReturn(network)
        val caps = Mockito.mock(NetworkCapabilities::class.java)
        Mockito.`when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        Mockito.`when`(manager.getNetworkCapabilities(network)).thenReturn(caps)
        val monitor = AndroidVoiceNetworkMonitor(context)
        assertFalse(monitor.start { fail("Unexpected callback") })
        Mockito.verify(manager, Mockito.never()).registerDefaultNetworkCallback(Mockito.any(), Mockito.any(Handler::class.java))
        Mockito.`when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        var observer: ConnectivityManager.NetworkCallback? = null
        Mockito.doAnswer { observer = it.getArgument(0); null }.`when`(manager)
            .registerDefaultNetworkCallback(Mockito.any(), Mockito.any(Handler::class.java))
        assertTrue(monitor.start {})
        monitor.stop(); Mockito.verify(manager).unregisterNetworkCallback(observer!!)
    }
    @Test fun lossOfValidationStopsAndOldNetworkOrCallbackCannotStopANewSession() {
        val context = Mockito.mock(Context::class.java)
        val manager = Mockito.mock(ConnectivityManager::class.java)
        val original = Mockito.mock(Network::class.java)
        val replacement = Mockito.mock(Network::class.java)
        Mockito.`when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(manager)
        Mockito.`when`(manager.activeNetwork).thenReturn(original)
        val caps = Mockito.mock(NetworkCapabilities::class.java)
        Mockito.`when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        Mockito.`when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        Mockito.`when`(manager.getNetworkCapabilities(original)).thenReturn(caps)
        var observer: ConnectivityManager.NetworkCallback? = null
        Mockito.doAnswer { observer = it.getArgument(0); null }.`when`(manager)
            .registerDefaultNetworkCallback(Mockito.any(), Mockito.any(Handler::class.java))
        val monitor = AndroidVoiceNetworkMonitor(context)
        var losses = 0
        assertTrue(monitor.start { losses++ })
        val old = observer!!
        old.onAvailable(replacement); old.onCapabilitiesChanged(replacement, caps)
        old.onLost(original); assertEquals(0, losses)
        old.onCapabilitiesChanged(replacement, NetworkCapabilities()); assertEquals(1, losses)
        monitor.stop(); old.onLost(replacement); assertEquals(1, losses)
        assertTrue(monitor.start { losses++ })
        old.onLost(original); assertEquals(1, losses)
        observer!!.onLost(original); assertEquals(2, losses)
        monitor.stop()
    }
}
