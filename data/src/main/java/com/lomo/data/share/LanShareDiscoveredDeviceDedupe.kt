package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice

internal fun mergeLanShareDiscoveredDevices(
    existing: List<DiscoveredDevice>,
    incoming: List<DiscoveredDevice>,
): List<DiscoveredDevice> {
    if (incoming.isEmpty()) return existing
    val distinctIncoming = incoming.distinctBy(DiscoveredDevice::lanShareEndpointKey)
    val incomingKeys = distinctIncoming.map(DiscoveredDevice::lanShareEndpointKey).toSet()
    return existing.filterNot { device -> device.lanShareEndpointKey() in incomingKeys } + distinctIncoming
}

internal fun DiscoveredDevice.lanShareEndpointKey(): String = "$host:$port"
