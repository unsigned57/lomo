package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice

internal fun mergeLanShareDiscoveredDevices(
    existing: List<DiscoveredDevice>,
    incoming: List<DiscoveredDevice>,
): List<DiscoveredDevice> {
    if (incoming.isEmpty()) return existing
    val merged = existing.toMutableList()
    incoming.forEach { device ->
        merged.removeAll { current -> current.representsSameLanSharePeer(device) }
        merged += device
    }
    return merged
}

internal fun DiscoveredDevice.lanShareEndpointKey(): String = "$host:$port"

internal fun DiscoveredDevice.lanShareIdentityKey(): String =
    "device:$deviceId"

private fun DiscoveredDevice.representsSameLanSharePeer(other: DiscoveredDevice): Boolean =
    deviceId == other.deviceId || lanShareEndpointKey() == other.lanShareEndpointKey()
