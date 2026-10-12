package moe.shizuku.manager.worker

import android.app.Application
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What `AdbStartWorker.enqueue` asks WorkManager to wait for (satisfiability checks need API 30+). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbStartWorkerConstraintsTest {

    private fun wifiRequest() =
        AdbStartWorker.buildConstraints(wifiRequired = true).requiredNetworkRequest.also { assertNotNull(it) }!!

    @Test
    fun noNetworkConstraintWhenWifiIsNotRequired() {
        val c = AdbStartWorker.buildConstraints(wifiRequired = false)

        assertEquals(NetworkType.NOT_REQUIRED, c.requiredNetworkType)
        assertNull(c.requiredNetworkRequest)
    }

    @Test
    fun wifiRequiredIsAWifiTransportRequestNotAnUnmeteredType() {
        val c = AdbStartWorker.buildConstraints(wifiRequired = true)

        assertEquals(NetworkType.NOT_REQUIRED, c.requiredNetworkType)
        assertTrue(wifiRequest().hasTransport(TRANSPORT_WIFI))
    }

    @Test
    fun wifiRequiredRejectsUnmeteredCellular() {
        val nc = NetworkFixtures.cellularNotMetered()
        assertFalse("${wifiRequest()} vs $nc", wifiRequest().canBeSatisfiedBy(nc))
    }

    @Test
    fun wifiRequiredRejectsOtherNonWifiTransports() {
        val nc = NetworkFixtures.ethernet()
        assertFalse("${wifiRequest()} vs $nc", wifiRequest().canBeSatisfiedBy(nc))
    }

    @Test
    fun wifiRequiredAcceptsUnmeteredWifi() {
        val nc = NetworkFixtures.wifiUnmetered()
        assertTrue("${wifiRequest()} vs $nc", wifiRequest().canBeSatisfiedBy(nc))
    }

    @Test
    fun wifiRequiredAcceptsMeteredWifiSuchAsAHotspot() {
        val nc = NetworkFixtures.wifiMetered()
        assertTrue("${wifiRequest()} vs $nc", wifiRequest().canBeSatisfiedBy(nc))
    }

    @Test
    fun wifiRequiredAcceptsVpnRunningOverWifi() {
        val nc = NetworkFixtures.vpnOverWifi()
        assertTrue("${wifiRequest()} vs $nc", wifiRequest().canBeSatisfiedBy(nc))
    }

    @Test
    fun wifiRequiredRejectsVpnRunningOverCellular() {
        val nc = NetworkFixtures.vpnOverCellular()
        assertFalse("${wifiRequest()} vs $nc", wifiRequest().canBeSatisfiedBy(nc))
    }

    @Test
    fun wifiRequiredIsNotSatisfiedWithoutAnyNetwork() {
        assertFalse(wifiRequest().canBeSatisfiedBy(null as NetworkCapabilities?))
    }

    /**
     * API 28-29: WorkManager registers the request with registerNetworkCallback and the platform
     * matches it against every network, so only the request's shape can be asserted here (Robolectric
     * has no platform matcher and NetworkRequest.canBeSatisfiedBy needs API 30).
     */
    @Test
    @Config(sdk = [28], application = Application::class)
    fun api28RequestIsWifiTransportWithoutUnmeteredOrNotVpnRequirement() {
        val c = AdbStartWorker.buildConstraints(wifiRequired = true)
        val request = c.requiredNetworkRequest!!

        assertEquals(NetworkType.NOT_REQUIRED, c.requiredNetworkType)
        assertTrue(request.hasTransport(TRANSPORT_WIFI))
        assertFalse(request.hasCapability(NET_CAPABILITY_NOT_METERED))
        assertFalse(request.hasCapability(NET_CAPABILITY_NOT_VPN))
    }

    /** minSdk is 24; WorkManager only honours a NetworkRequest from API 28, otherwise it uses the type. */
    @Test
    @Config(sdk = [24], application = Application::class)
    fun belowApi28KeepsTheUnmeteredTypeBecauseNoWifiTypeExists() {
        val c = AdbStartWorker.buildConstraints(wifiRequired = true)

        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertNull(c.requiredNetworkRequest)
    }

    @Test
    @Config(sdk = [24], application = Application::class)
    fun belowApi28NoConstraintWhenWifiIsNotRequired() {
        val c = AdbStartWorker.buildConstraints(wifiRequired = false)

        assertEquals(NetworkType.NOT_REQUIRED, c.requiredNetworkType)
        assertNull(c.requiredNetworkRequest)
    }
}
