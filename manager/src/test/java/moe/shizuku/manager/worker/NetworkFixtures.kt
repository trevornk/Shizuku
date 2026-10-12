package moe.shizuku.manager.worker

import android.net.NetworkCapabilities
import android.os.Build
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Capability sets modelled on what ConnectivityService really publishes. A VPN network carries
 * TRANSPORT_VPN plus the transports of its underlying networks and does NOT carry NOT_VPN
 * (see ConnectivityService.applyUnderlyingCapabilities / Vpn.java).
 */
object NetworkFixtures {
    /** NET_CAPABILITY_NOT_VCN_MANAGED is hidden from the public SDK. */
    private const val NET_CAPABILITY_NOT_VCN_MANAGED = 28

    private fun caps(
        transports: List<Int>,
        capabilities: List<Int>,
        vpn: Boolean = false,
    ): NetworkCapabilities {
        val nc = ShadowNetworkCapabilities.newInstance()
        val shadow = org.robolectric.Shadows.shadowOf(nc)
        transports.forEach { shadow.addTransportType(it) }
        capabilities.forEach { shadow.addCapability(it) }
        if (vpn) shadow.removeCapability(NET_CAPABILITY_NOT_VPN)
        return nc
    }

    // NetworkRequest.Builder requires NOT_RESTRICTED and TRUSTED (and deduces NOT_VCN_MANAGED on API 31+);
    // real Wi-Fi, cellular and VPN networks carry all three (Vpn.java adds NOT_VCN_MANAGED explicitly).
    /** NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED: NetworkRequest.Builder requires it from API 35. */
    private const val NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED = 37

    private val usable = listOf(
        NET_CAPABILITY_INTERNET,
        NET_CAPABILITY_VALIDATED,
        NET_CAPABILITY_NOT_SUSPENDED,
        NET_CAPABILITY_NOT_RESTRICTED,
        NET_CAPABILITY_TRUSTED,
        NET_CAPABILITY_NOT_VCN_MANAGED,
    ) + if (Build.VERSION.SDK_INT >= 35) listOf(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED) else emptyList()

    /** The Fold's cellular network: NOT_METERED + TEMPORARILY_NOT_METERED, which satisfied UNMETERED. */
    fun cellularNotMetered() = caps(
        listOf(TRANSPORT_CELLULAR),
        usable + listOf(NET_CAPABILITY_NOT_METERED, NET_CAPABILITY_TEMPORARILY_NOT_METERED, NET_CAPABILITY_NOT_ROAMING),
    )

    fun wifiUnmetered() = caps(listOf(TRANSPORT_WIFI), usable + NET_CAPABILITY_NOT_METERED)

    /** A phone hotspot or other metered AP: no NOT_METERED capability. */
    fun wifiMetered() = caps(listOf(TRANSPORT_WIFI), usable)

    fun ethernet() = caps(listOf(TRANSPORT_ETHERNET), usable + NET_CAPABILITY_NOT_METERED)

    fun vpnOverWifi() = caps(listOf(TRANSPORT_VPN, TRANSPORT_WIFI), usable, vpn = true)

    fun vpnOverCellular() = caps(
        listOf(TRANSPORT_VPN, TRANSPORT_CELLULAR),
        usable + NET_CAPABILITY_NOT_METERED,
        vpn = true,
    )
}
