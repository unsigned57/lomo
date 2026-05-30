package com.lomo.app.feature.share

internal enum class DeviceDiscoveryContentState {
    Searching,
    PermissionDenied,
    StartupFailed,
    Devices,
}

internal fun resolveDeviceDiscoveryContentState(
    discoveredDeviceCount: Int,
    permissionState: LanSharePermissionState,
    discoveryError: String?,
    activeFallbackDiscovery: Boolean,
): DeviceDiscoveryContentState =
    when {
        discoveredDeviceCount > 0 -> DeviceDiscoveryContentState.Devices
        permissionState == LanSharePermissionState.Denied -> DeviceDiscoveryContentState.PermissionDenied
        discoveryError != null && !activeFallbackDiscovery -> DeviceDiscoveryContentState.StartupFailed
        else -> DeviceDiscoveryContentState.Searching
    }
