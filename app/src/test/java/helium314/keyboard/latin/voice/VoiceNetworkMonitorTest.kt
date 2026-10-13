// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class VoiceNetworkMonitorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val shadow = shadowOf(connectivity)
    private val monitor = AndroidVoiceNetworkMonitor(context)

    private fun connected() {
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadow.setNetworkCapabilities(connectivity.activeNetwork, capabilities(true))
    }
    private fun capabilities(validated: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Test fun offlineAndCaptivePortalPreflightFail() {
        shadow.setActiveNetworkInfo(null)
        assertFalse(monitor.start { error("not started") })
        connected()
        shadow.setNetworkCapabilities(connectivity.activeNetwork, capabilities(false))
        assertFalse(monitor.start { error("not started") })
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun capabilityLossAndRouteLossNotifyOnTheMainLooper() {
        connected(); var losses = 0
        assertTrue(monitor.start { losses++ })
        val observer = shadow.networkCallbacks.single()
        observer.onCapabilitiesChanged(connectivity.activeNetwork!!, capabilities(false))
        assertEquals(0, losses)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, losses)
        observer.onLost(connectivity.activeNetwork!!)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, losses)
        monitor.stop()
    }

    @Test fun validatedHandoffDoesNotTreatOldRouteLossAsLossOfTheNewRoute() {
        connected(); var losses = 0
        assertTrue(monitor.start { losses++ })
        val old = connectivity.activeNetwork!!
        val replacement = ShadowNetwork.newInstance(987)
        val observer = shadow.networkCallbacks.single()
        observer.onAvailable(replacement)
        observer.onCapabilitiesChanged(replacement, capabilities(true))
        observer.onLost(old)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, losses)
        observer.onLost(replacement)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, losses)
        monitor.stop()
    }

    @Test fun stopAndRestartInvalidateAlreadyPostedCallbacks() {
        connected(); var losses = 0
        assertTrue(monitor.start { losses++ })
        val stale = shadow.networkCallbacks.single()
        stale.onLost(connectivity.activeNetwork!!)
        monitor.stop()
        assertTrue(monitor.start { losses++ })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, losses)
        assertEquals(1, shadow.networkCallbacks.size)
        monitor.stop()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test @Config(sdk = [21])
    fun api21UsesLegacyConnectivityAndCleansUpItsReceiver() {
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        var losses = 0
        assertTrue(monitor.start { losses++ })
        shadow.setActiveNetworkInfo(null)
        context.sendBroadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, losses)
        monitor.stop()
        context.sendBroadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, losses)
    }
}
