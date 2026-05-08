package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal fun mapResolvedLanShareDevice(
    serviceName: String,
    hostAddresses: List<InetAddress>,
    port: Int,
    attributes: Map<String, ByteArray>,
    localUuid: String?,
): DiscoveredDevice? {
    if (port <= 0) return null

    val remoteUuid = attributes["uuid"]?.let { String(it, Charsets.UTF_8) }
    if (localUuid != null && remoteUuid == localUuid) return null

    val hostAddress = selectLanShareHostAddress(hostAddresses) ?: return null
    return DiscoveredDevice(
        name = serviceName.removePrefix(NsdDiscoveryService.SERVICE_NAME_PREFIX),
        host = hostAddress.toLanShareHttpHost() ?: return null,
        port = port,
    )
}

private fun selectLanShareHostAddress(hostAddresses: List<InetAddress>): InetAddress? =
    hostAddresses.firstOrNull { it is Inet4Address }
        ?: hostAddresses.firstOrNull { it is Inet6Address }

private fun InetAddress.toLanShareHttpHost(): String? {
    val rawHost = hostAddress ?: return null
    return when (this) {
        is Inet6Address -> "[${rawHost.replace("%", "%25")}]"
        else -> rawHost
    }
}
