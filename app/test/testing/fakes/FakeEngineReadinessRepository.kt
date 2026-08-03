package com.lomo.app.testing.fakes

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.RecoveryWorkspaceKind
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
            WorkspaceAuthority(workspaceId = "fake-workspace", generation = 0),
        )
    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()
    var activateCount: Int = 0
        private set
    var clearCount: Int = 0
        private set
    var diagnosticExportCount: Int = 0
        private set
    var derivedIndexRebuildCount: Int = 0
        private set
    var lastActivated: StorageLocation? = null
        private set

    fun publish(value: EngineReadiness) {
        _readiness.value = value
    }

    override fun resnapshot() = Unit

    override suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport {
        diagnosticExportCount += 1
        val recovery = readiness.value as? EngineReadiness.ReadOnlyRecovery
            ?: error("fake is not in recovery")
        return recovery.toDiagnosticReport(workspaceKind())
    }

    override suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary {
        derivedIndexRebuildCount += 1
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
        _activeWorkspaceLocation.value = location
        _workspaceAuthority.value =
            WorkspaceAuthority(workspaceId = location.raw, generation = activateCount.toLong())
        _readiness.value =
            EngineReadiness.Ready(coreRevision = 0uL, eventSequence = activateCount.toULong())
    }

    override suspend fun clearWorkspace() {
        clearCount += 1
        lastActivated = null
        _activeWorkspaceLocation.value = null
        _workspaceAuthority.value = null
        _readiness.value = EngineReadiness.AwaitingWorkspaceSelection
    }
}
