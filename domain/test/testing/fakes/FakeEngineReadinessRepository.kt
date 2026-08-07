package com.lomo.domain.testing.fakes

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
    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()
    override val projectionFreshness: StateFlow<ProjectionFreshness> =
        _projectionFreshness.asStateFlow()
    var resnapshotCount: Int = 0
        private set
    var activateCount: Int = 0
    var clearCount: Int = 0
    var lastActivated: StorageLocation? = null
        private set
    /** Fail the next [activateWorkspace] call this many times, then succeed. */
    var remainingActivateFailures: Int = 0
    var activateFailure: Throwable = IllegalStateException("activate failed")
    var activateResult: EngineReadiness? = null

    fun publish(value: EngineReadiness) {
        _readiness.value = value
    }

    override fun resnapshot() {
        resnapshotCount += 1
    }

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
        lastActivated = location
        if (remainingActivateFailures > 0) {
            remainingActivateFailures -= 1
            throw activateFailure
        }
        _activeWorkspaceLocation.value = location
        _workspaceAuthority.value =
            WorkspaceAuthority(
                workspaceId = location.raw,
                generation = activateCount.toLong(),
                projectionRevision = activateCount.toULong(),
            )
        _projectionFreshness.value = ProjectionFreshness.Verified(activateCount.toULong())
        _readiness.value =
            activateResult
                ?: EngineReadiness.Ready(coreRevision = 0uL, eventSequence = activateCount.toULong())
    }

    override suspend fun clearWorkspace() {
        clearCount += 1
        _readiness.value = EngineReadiness.AwaitingWorkspaceSelection
        _activeWorkspaceLocation.value = null
        _workspaceAuthority.value = null
        _projectionFreshness.value = ProjectionFreshness.Unavailable
        lastActivated = null
    }
}
