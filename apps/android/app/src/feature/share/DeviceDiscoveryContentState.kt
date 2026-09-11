package com.lomo.app.feature.share

import com.lomo.domain.model.LanShareActiveProbeState
import com.lomo.domain.model.LanShareDiscoveryDiagnostics

internal enum class DeviceDiscoveryContentState {
    Searching,
    PermissionDenied,
    StartupFailed,
    Devices,
}

internal enum class DeviceDiscoverySearchHint {
    SameWifi,
    ProbeBackoff,
    DegradedRoute,
}

internal fun resolveDeviceDiscoveryContentState(
    discoveredDeviceCount: Int,
    permissionState: LanSharePermissionState,
    discoveryError: String?,
    diagnostics: LanShareDiscoveryDiagnostics,
): DeviceDiscoveryContentState =
    when {
        discoveredDeviceCount > 0 -> DeviceDiscoveryContentState.Devices
        permissionState == LanSharePermissionState.Denied -> DeviceDiscoveryContentState.PermissionDenied
        discoveryError != null && !diagnostics.isActiveProbeRecovering() -> DeviceDiscoveryContentState.StartupFailed
        else -> DeviceDiscoveryContentState.Searching
    }

internal fun resolveDeviceDiscoverySearchHint(
    diagnostics: LanShareDiscoveryDiagnostics,
): DeviceDiscoverySearchHint =
    when (diagnostics.activeProbe.state) {
        LanShareActiveProbeState.DegradedNoRoute -> DeviceDiscoverySearchHint.DegradedRoute
        LanShareActiveProbeState.BackingOff -> DeviceDiscoverySearchHint.ProbeBackoff
        LanShareActiveProbeState.Idle,
        LanShareActiveProbeState.Scanning,
        -> DeviceDiscoverySearchHint.SameWifi
    }

private fun LanShareDiscoveryDiagnostics.isActiveProbeRecovering(): Boolean =
    discoveryDesired &&
        activeProbe.state in
        setOf(
            LanShareActiveProbeState.Scanning,
            LanShareActiveProbeState.BackingOff,
            LanShareActiveProbeState.DegradedNoRoute,
        )
