package com.cyenx.clashmi.clashmi_vpn_service

import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.Inet4Address

internal fun forkDefaultGateway(routes: List<Pair<Boolean, InetAddress?>>): String? =
    routes.filter { it.first && it.second != null && !it.second!!.isAnyLocalAddress }
        .sortedBy { if (it.second is Inet4Address) 0 else 1 }
        .firstOrNull()?.second?.hostAddress?.substringBefore('%')

@Suppress("UNUSED_PARAMETER") // The private receiver policy is identical on every supported API.
internal fun forkLocationReceiverFlags(api: Int): Int = ContextCompat.RECEIVER_NOT_EXPORTED

internal fun forkNetworkTransport(wifi: Boolean, wired: Boolean, cellular: Boolean): String = when {
    wifi -> "wifi"
    wired -> "wired"
    cellular -> "cellular"
    else -> ""
}

internal fun forkWifiFields(transport: String, ssid: String?, bssid: String?): Map<String, String> =
    if (transport != "wifi") emptyMap() else buildMap {
        ssid?.let { put("ssid", it) }
        bssid?.let { put("bssid", it) }
    }

internal fun <N, C, L> forkNetworkReady(network: N, caps: Map<N, C>, links: Map<N, L>): Boolean =
    caps.containsKey(network) && links.containsKey(network)

internal fun forkRefreshRegistration(running: Boolean, unregister: () -> Unit, register: () -> Unit) {
    unregister()
    if (running) register()
}

internal inline fun <T> forkReportBeforeStart(report: () -> Unit, start: () -> T): T {
    report()
    return start()
}

internal fun forkNetworkPermissionSignature(permissions: List<Int>, gps: Boolean, network: Boolean): String =
    permissions.joinToString() + ":$gps:$network"
