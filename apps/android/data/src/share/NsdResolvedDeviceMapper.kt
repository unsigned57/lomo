package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice
import java.net.Inet4Address
import java.net.InetAddress

internal fun mapResolvedLanShareDevice(
    serviceName: String,
    hostAddresses: List<InetAddress>,
    port: Int,
    attributes: Map<String, ByteArray>,
    localDeviceId: String,
): DiscoveredDevice? {
    val protocolVersion = attributes["protocol_version"]?.let { value -> String(value, Charsets.UTF_8) }
    val remoteDeviceId =
        attributes["device_id"]?.let { value -> String(value, Charsets.UTF_8) }
        ?.takeIf { deviceId ->
            deviceId.length == DEVICE_ID_HEX_LENGTH &&
                deviceId.all { character -> character in '0'..'9' || character in 'a'..'f' }
        }
    val host = selectLanShareHostAddress(hostAddresses)?.hostAddress?.substringBefore('%')
    val endpointIsValid =
        port in 1..UShort.MAX_VALUE.toInt() && protocolVersion == "2" && host != null
    return if (!endpointIsValid || remoteDeviceId == null || remoteDeviceId == localDeviceId) {
        null
    } else {
        DiscoveredDevice(
            deviceId = remoteDeviceId,
            name = serviceName.removePrefix(NsdDiscoveryService.SERVICE_NAME_PREFIX),
            host = checkNotNull(host),
            port = port,
        )
    }
}

private fun selectLanShareHostAddress(hostAddresses: List<InetAddress>): InetAddress? =
    hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull()

private const val DEVICE_ID_HEX_LENGTH = 64
