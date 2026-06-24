package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareActiveProbeDiagnostics
import com.lomo.domain.model.LanShareActiveProbeState
import com.lomo.domain.model.LanShareDiscoveryDegradedReason
import com.lomo.domain.model.LanShareProbeRouteState
import com.lomo.domain.model.LanShareRouteSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

internal class LanShareActiveDiscoveryLoop(
    private val scope: CoroutineScope,
    private val activeDiscoveryClient: LanShareActiveDiscoveryScanner,
    private val discoveryCoordinator: LanShareDiscoveryCoordinator,
    private val isDiscoveryStarted: () -> Boolean,
    private val activeSnapshots: () -> List<LanShareActiveNetworkSnapshot>,
    private val resolveEligibleSnapshots: () -> List<LanShareActiveNetworkSnapshot>,
    private val publishDiagnostics: (LanShareActiveDiscoveryDiagnosticsUpdate) -> Unit,
) {
    private var activeDiscoveryJob: Job? = null

    val isRunning: Boolean
        get() = activeDiscoveryJob?.isActive == true

    val hasJob: Boolean
        get() = activeDiscoveryJob != null

    fun restart(snapshots: List<LanShareActiveNetworkSnapshot>) {
        activeDiscoveryJob?.cancel()
        start(snapshots)
    }

    fun startIfIdle(snapshots: List<LanShareActiveNetworkSnapshot>) {
        if (!isRunning) {
            start(snapshots)
        }
    }

    fun stop() {
        activeDiscoveryJob?.cancel()
        activeDiscoveryJob = null
    }

    private fun start(snapshots: List<LanShareActiveNetworkSnapshot>) {
        activeDiscoveryJob =
            scope.launch {
                val schedulePolicy = LanShareActiveDiscoverySchedulePolicy()
                while (isActive && isDiscoveryStarted()) {
                    val delayMs = schedulePolicy.delayBeforeNextScanMs()
                    if (delayMs > 0) delay(delayMs)
                    if (!isActive || !isDiscoveryStarted()) {
                        return@launch
                    }
                    val result = runActiveDiscoveryScan(snapshots)
                    schedulePolicy.recordScanResult(result.foundDeviceCount)
                    publishDiagnostics(result.toDiagnosticsUpdate(schedulePolicy.delayBeforeNextScanMs()))
                }
            }
    }

    private suspend fun runActiveDiscoveryScan(
        seedSnapshots: List<LanShareActiveNetworkSnapshot>,
    ): LanShareActiveDiscoveryLoopScanResult {
        val snapshots =
            seedSnapshots
                .ifEmpty(activeSnapshots)
                .ifEmpty(resolveEligibleSnapshots)
        if (snapshots.isEmpty()) {
            return LanShareActiveDiscoveryLoopScanResult(
                devices = emptyList(),
                routeSnapshots = emptyList(),
                activeProbe =
                    LanShareActiveProbeDiagnostics(
                        state = LanShareActiveProbeState.Idle,
                    ),
                degradedReason = null,
            )
        }

        val scanResults =
            runCatching {
                coroutineScope {
                    snapshots
                        .map { snapshot ->
                            async {
                                scanSnapshot(snapshot)
                            }
                        }.awaitAll()
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).d(error, "LAN active discovery scan aggregation failed")
                snapshots.map { snapshot -> snapshot.toFailedScanResult() }
            }

        val merged =
            scanResults
                .flatMap(LanShareActiveDiscoveryScanResult::devices)
                .distinctBy(DiscoveredDevice::lanShareEndpointKey)

        if (isDiscoveryStarted() && merged.isNotEmpty()) {
            discoveryCoordinator.mergeDiscoveredDevices(merged)
        }
        if (merged.isEmpty()) {
            Timber
                .tag(TAG)
                .d(
                    "LAN active discovery scan returned no peers across %d snapshots",
                    snapshots.size,
                )
        }
        return scanResults.toLoopScanResult(merged)
    }

    private suspend fun scanSnapshot(snapshot: LanShareActiveNetworkSnapshot): LanShareActiveDiscoveryScanResult =
        runCatching { activeDiscoveryClient.scan(snapshot) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                Timber
                    .tag(TAG)
                    .d(
                        error,
                        "LAN active discovery scan failed for %s",
                        snapshot.networkKey,
                    )
                snapshot.toFailedScanResult()
            }

    private fun List<LanShareActiveDiscoveryScanResult>.toLoopScanResult(
        mergedDevices: List<DiscoveredDevice>,
    ): LanShareActiveDiscoveryLoopScanResult {
        val routeCapableCount = count { result -> result.snapshot.routeState == LanShareProbeRouteState.BoundNetwork }
        val degradedCount = count { result -> result.snapshot.routeState == LanShareProbeRouteState.DegradedNoNetwork }
        val degradedReason = firstNotNullOfOrNull(LanShareActiveDiscoveryScanResult::degradedReason)
        val activeProbeState =
            when {
                isEmpty() -> LanShareActiveProbeState.Idle
                routeCapableCount == 0 && degradedCount > 0 -> LanShareActiveProbeState.DegradedNoRoute
                else -> LanShareActiveProbeState.Scanning
            }
        return LanShareActiveDiscoveryLoopScanResult(
            devices = mergedDevices,
            routeSnapshots = map(LanShareActiveDiscoveryScanResult::snapshot),
            activeProbe =
                LanShareActiveProbeDiagnostics(
                    state = activeProbeState,
                    snapshotCount = size,
                    routeCapableSnapshotCount = routeCapableCount,
                    degradedSnapshotCount = degradedCount,
                    targetBudget = ACTIVE_DISCOVERY_TARGET_BUDGET,
                    scanWindowOffset =
                        firstOrNull { result ->
                            result.snapshot.routeState == LanShareProbeRouteState.BoundNetwork
                        }?.activeProbe?.scanWindowOffset ?: 0,
                    probedTargetCount = sumOf { result -> result.activeProbe.probedTargetCount },
                    foundDeviceCount = mergedDevices.size,
                ),
            degradedReason = degradedReason,
        )
    }

    private fun LanShareActiveNetworkSnapshot.toFailedScanResult(): LanShareActiveDiscoveryScanResult =
        LanShareActiveDiscoveryScanResult(
            devices = emptyList(),
            snapshot = toRouteSnapshot(),
            activeProbe =
                LanShareActiveProbeDiagnostics(
                    state =
                        if (network == null) {
                            LanShareActiveProbeState.DegradedNoRoute
                        } else {
                            LanShareActiveProbeState.BackingOff
                        },
                    snapshotCount = 1,
                    routeCapableSnapshotCount = if (network == null) 0 else 1,
                    degradedSnapshotCount = if (network == null) 1 else 0,
                    targetBudget = ACTIVE_DISCOVERY_TARGET_BUDGET,
                    probedTargetCount = 0,
                    foundDeviceCount = 0,
                ),
            degradedReason = LanShareDiscoveryDegradedReason.ActiveProbeScanFailed,
        )

    private fun LanShareActiveDiscoveryLoopScanResult.toDiagnosticsUpdate(
        nextScanDelayMs: Long,
    ): LanShareActiveDiscoveryDiagnosticsUpdate {
        val scheduledState =
            when {
                activeProbe.state == LanShareActiveProbeState.DegradedNoRoute ->
                    LanShareActiveProbeState.DegradedNoRoute
                activeProbe.foundDeviceCount == 0 && nextScanDelayMs > 0L -> LanShareActiveProbeState.BackingOff
                else -> activeProbe.state
            }
        return LanShareActiveDiscoveryDiagnosticsUpdate(
            activeProbe =
                activeProbe.copy(
                    state = scheduledState,
                    nextScanDelayMs = nextScanDelayMs,
                ),
            routeSnapshots = routeSnapshots,
            degradedReason = degradedReason,
        )
    }

    private companion object {
        private const val TAG = "LanShareActiveDiscoveryLoop"
    }
}

internal data class LanShareActiveDiscoveryDiagnosticsUpdate(
    val activeProbe: LanShareActiveProbeDiagnostics,
    val routeSnapshots: List<LanShareRouteSnapshot>,
    val degradedReason: LanShareDiscoveryDegradedReason?,
)

private data class LanShareActiveDiscoveryLoopScanResult(
    val devices: List<DiscoveredDevice>,
    val routeSnapshots: List<LanShareRouteSnapshot>,
    val activeProbe: LanShareActiveProbeDiagnostics,
    val degradedReason: LanShareDiscoveryDegradedReason?,
) {
    val foundDeviceCount: Int
        get() = devices.size
}
