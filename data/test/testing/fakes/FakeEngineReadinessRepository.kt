package com.lomo.data.testing.fakes

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.RecoveryWorkspaceKind
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.canRebuildDerivedIndex
import com.lomo.domain.model.toDiagnosticReport
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-test readiness collaborator for data-layer write-gate contracts. */
class FakeEngineReadinessRepository(
    initial: EngineReadiness = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 0uL),
) : EngineReadinessRepository {
    private val _readiness = MutableStateFlow(initial)
    private val _activeWorkspaceLocation = MutableStateFlow<StorageLocation?>(null)
    private val _workspaceAuthority =
        MutableStateFlow<WorkspaceAuthority?>(
            WorkspaceAuthority(workspaceId = "fake-workspace", generation = 0, projectionRevision = 0uL),
        )
    private val _projectionFreshness =
        MutableStateFlow<ProjectionFreshness>(ProjectionFreshness.Verified(0uL))
    private var activateCount = 0
    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()
    override val projectionFreshness: StateFlow<ProjectionFreshness> =
        _projectionFreshness.asStateFlow()

    fun publish(value: EngineReadiness) {
        _readiness.value = value
    }

    override fun resnapshot() = Unit

    override suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport {
        val recovery = readiness.value as? EngineReadiness.ReadOnlyRecovery
            ?: error("fake is not in recovery")
        return recovery.toDiagnosticReport(workspaceKind())
    }

    override suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary {
        val recovery = readiness.value as? EngineReadiness.ReadOnlyRecovery
            ?: error("fake is not in recovery")
        require(recovery.canRebuildDerivedIndex())
        _readiness.value = EngineReadiness.Ready(coreRevision = 1uL, eventSequence = 1uL)
        return DerivedIndexRebuildSummary(0uL, 0uL, 0uL, 0uL, 1uL)
    }

    private fun workspaceKind(): RecoveryWorkspaceKind =
        when (val location = activeWorkspaceLocation.value?.raw) {
            null -> RecoveryWorkspaceKind.NONE
            else -> if (location.startsWith("content://")) RecoveryWorkspaceKind.SAF else RecoveryWorkspaceKind.DIRECT
        }

    override suspend fun activateWorkspace(location: StorageLocation) {
        activateCount += 1
        _activeWorkspaceLocation.value = location
        _workspaceAuthority.value =
            WorkspaceAuthority(
                workspaceId = location.raw,
                generation = activateCount.toLong(),
                projectionRevision = activateCount.toULong(),
            )
        _projectionFreshness.value = ProjectionFreshness.Verified(activateCount.toULong())
        _readiness.value = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 1uL)
    }

    override suspend fun clearWorkspace() {
        _activeWorkspaceLocation.value = null
        _workspaceAuthority.value = null
        _projectionFreshness.value = ProjectionFreshness.Unavailable
        _readiness.value = EngineReadiness.AwaitingWorkspaceSelection
    }
}
