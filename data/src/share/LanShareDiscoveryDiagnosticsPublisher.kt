package com.lomo.data.share

import com.lomo.domain.model.LanShareActiveProbeDiagnostics
import com.lomo.domain.model.LanShareActiveProbeState
import com.lomo.domain.model.LanShareDiscoveryDegradedReason
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import com.lomo.domain.model.LanShareRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class LanShareDiscoveryDiagnosticsPublisher(
    private val runtimeSnapshot: () -> LanShareDiagnosticsRuntimeSnapshot,
) {
    private val _runtimeState = MutableStateFlow(LanShareRuntimeState.Stopped)
    val runtimeState: StateFlow<LanShareRuntimeState> = _runtimeState.asStateFlow()

    private val _diagnostics = MutableStateFlow(LanShareDiscoveryDiagnostics())
    val diagnostics: StateFlow<LanShareDiscoveryDiagnostics> = _diagnostics.asStateFlow()

    val currentActiveProbe: LanShareActiveProbeDiagnostics
        get() = _diagnostics.value.activeProbe

    val currentDegradedReason: LanShareDiscoveryDegradedReason?
        get() = _diagnostics.value.lastDegradedReason

    fun publishRuntimeState(
        state: LanShareRuntimeState,
        activeProbe: LanShareActiveProbeDiagnostics = currentActiveProbe,
        degradedReason: LanShareDiscoveryDegradedReason? = currentDegradedReason,
    ) {
        _runtimeState.value = state
        _diagnostics.value =
            buildSnapshot(
                runtimeState = state,
                runtimeSnapshot = runtimeSnapshot(),
                activeProbe = activeProbe,
                degradedReason = degradedReason,
            )
    }

    fun publishActiveDiscoveryDiagnostics(
        update: LanShareActiveDiscoveryDiagnosticsUpdate,
    ) {
        _diagnostics.update { current ->
            val degradedReason = update.degradedReason ?: current.lastDegradedReason
            val snapshot =
                buildSnapshot(
                    runtimeState = _runtimeState.value,
                    runtimeSnapshot = runtimeSnapshot(),
                    activeProbe = update.activeProbe,
                    degradedReason = degradedReason,
                )
            if (update.routeSnapshots.isEmpty()) {
                snapshot
            } else {
                snapshot.copy(discoverySnapshots = update.routeSnapshots)
            }
        }
    }

    private fun buildSnapshot(
        runtimeState: LanShareRuntimeState,
        runtimeSnapshot: LanShareDiagnosticsRuntimeSnapshot,
        activeProbe: LanShareActiveProbeDiagnostics,
        degradedReason: LanShareDiscoveryDegradedReason?,
    ): LanShareDiscoveryDiagnostics {
        return LanShareDiscoveryDiagnostics(
            runtimeState = runtimeState,
            servicesDesired = runtimeSnapshot.servicesDesired,
            discoveryDesired = runtimeSnapshot.discoveryDesired,
            serverPort = runtimeSnapshot.serverPort,
            serviceSnapshots = runtimeSnapshot.serviceSnapshots.map(LanShareActiveNetworkSnapshot::toRouteSnapshot),
            discoverySnapshots = runtimeSnapshot.discoverySnapshots.map(LanShareActiveNetworkSnapshot::toRouteSnapshot),
            activeProbe = activeProbe.forDiscoveryDesired(runtimeSnapshot.discoveryDesired),
            lastDegradedReason = degradedReason,
        )
    }

    private fun LanShareActiveProbeDiagnostics.forDiscoveryDesired(
        discoveryDesired: Boolean,
    ): LanShareActiveProbeDiagnostics =
        if (discoveryDesired) {
            this
        } else {
            copy(state = LanShareActiveProbeState.Idle, nextScanDelayMs = 0L)
        }
}

internal data class LanShareDiagnosticsRuntimeSnapshot(
    val servicesDesired: Boolean,
    val discoveryDesired: Boolean,
    val serviceSnapshots: List<LanShareActiveNetworkSnapshot>,
    val discoverySnapshots: List<LanShareActiveNetworkSnapshot>,
    val serverPort: Int,
)
