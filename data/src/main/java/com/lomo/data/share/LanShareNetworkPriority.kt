package com.lomo.data.share

import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

private const val LAN_SHARE_NETWORK_PRIORITY_WIFI = 0
private const val LAN_SHARE_NETWORK_PRIORITY_ETHERNET = 1
private const val LAN_SHARE_NETWORK_PRIORITY_LOCAL_ONLY = 2
private const val LAN_SHARE_INTERFACE_PRIORITY_AP = 0
private const val LAN_SHARE_INTERFACE_PRIORITY_WLAN = 1
private const val LAN_SHARE_INTERFACE_PRIORITY_ETH = 2
private const val LAN_SHARE_INTERFACE_PRIORITY_USB = 3
private const val LAN_SHARE_INTERFACE_PRIORITY_BRIDGE = 4
private const val LAN_SHARE_INTERFACE_PRIORITY_BLUETOOTH = 5

internal fun LanShareNetworkProbe.lanSharePriority(): Int? =
    when {
        hasWifiTransport -> LAN_SHARE_NETWORK_PRIORITY_WIFI
        hasEthernetTransport -> LAN_SHARE_NETWORK_PRIORITY_ETHERNET
        hasLocalNetworkCapability -> LAN_SHARE_NETWORK_PRIORITY_LOCAL_ONLY
        else -> null
    }

internal fun LanShareInterfaceProbe.lanShareInterfacePriority(): Int? {
    if (!isUp || isLoopback || isVirtual || isPointToPoint) return null
    return name.lanShareInterfacePriority()
}

private fun String.lanShareInterfacePriority(): Int? {
    val normalized = lowercase(Locale.ROOT)
    return when {
        normalized.startsWith("ap") -> LAN_SHARE_INTERFACE_PRIORITY_AP
        normalized.startsWith("swlan") -> LAN_SHARE_INTERFACE_PRIORITY_AP
        normalized.contains("softap") -> LAN_SHARE_INTERFACE_PRIORITY_AP
        normalized.startsWith("wlan") -> LAN_SHARE_INTERFACE_PRIORITY_WLAN
        normalized.startsWith("eth") -> LAN_SHARE_INTERFACE_PRIORITY_ETH
        normalized.startsWith("rndis") -> LAN_SHARE_INTERFACE_PRIORITY_USB
        normalized.startsWith("usb") -> LAN_SHARE_INTERFACE_PRIORITY_USB
        normalized.startsWith("br") -> LAN_SHARE_INTERFACE_PRIORITY_BRIDGE
        normalized.contains("bridge") -> LAN_SHARE_INTERFACE_PRIORITY_BRIDGE
        normalized.startsWith("bnep") -> LAN_SHARE_INTERFACE_PRIORITY_BLUETOOTH
        normalized.startsWith("bt-pan") -> LAN_SHARE_INTERFACE_PRIORITY_BLUETOOTH
        else -> null
    }
}

internal fun NetworkCapabilities.hasLanShareLocalNetworkCapability(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)

internal fun InetAddress.isLanSharePrivateAddress(): Boolean =
    isLoopbackAddress ||
        isSiteLocalAddress ||
        (this is Inet6Address && isLanShareUniqueLocalAddress())

private fun Inet6Address.isLanShareUniqueLocalAddress(): Boolean {
    val normalized = hostAddress?.substringBefore('%')?.lowercase(Locale.ROOT).orEmpty()
    return normalized.startsWith("fc") || normalized.startsWith("fd")
}
