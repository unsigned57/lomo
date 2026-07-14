package com.lomo.domain.model

data class LanShareDiscoveryDiagnostics(
    val runtimeState: LanShareRuntimeState = LanShareRuntimeState.Stopped,
    val servicesDesired: Boolean = false,
    val discoveryDesired: Boolean = false,
    val serverPort: Int = 0,
    val serviceSnapshots: List<LanShareRouteSnapshot> = emptyList(),
    val discoverySnapshots: List<LanShareRouteSnapshot> = emptyList(),
    val activeProbe: LanShareActiveProbeDiagnostics = LanShareActiveProbeDiagnostics(),
    val lastDegradedReason: LanShareDiscoveryDegradedReason? = null,
)

data class LanShareRouteSnapshot(
    val networkKey: String,
    val bindHost: String,
    val routeState: LanShareProbeRouteState,
)

enum class LanShareProbeRouteState {
    BoundNetwork,
    DegradedNoNetwork,
}

data class LanShareActiveProbeDiagnostics(
    val state: LanShareActiveProbeState = LanShareActiveProbeState.Idle,
    val snapshotCount: Int = 0,
    val routeCapableSnapshotCount: Int = 0,
    val degradedSnapshotCount: Int = 0,
    val targetBudget: Int = 0,
    val scanWindowOffset: Int = 0,
    val probedTargetCount: Int = 0,
    val foundDeviceCount: Int = 0,
    val nextScanDelayMs: Long = 0L,
)

enum class LanShareActiveProbeState {
    Idle,
    Scanning,
    BackingOff,
    DegradedNoRoute,
}

enum class LanShareDiscoveryDegradedReason {
    FallbackSnapshotWithoutNetwork,
    ActiveProbeScanFailed,
}
