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
    uuid?.let { value -> "uuid:$value" } ?: "endpoint:${lanShareEndpointKey()}"

private fun DiscoveredDevice.representsSameLanSharePeer(other: DiscoveredDevice): Boolean =
    (uuid != null && other.uuid != null && uuid == other.uuid) ||
        lanShareEndpointKey() == other.lanShareEndpointKey()
