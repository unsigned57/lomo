package com.lomo.data.testing.fakes

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
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
            WorkspaceAuthority(workspaceId = "fake-workspace", generation = 0),
        )
    private var activateCount = 0
    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()

    fun publish(value: EngineReadiness) {
        _readiness.value = value
    }

    override fun resnapshot() = Unit

    override suspend fun activateWorkspace(location: StorageLocation) {
        activateCount += 1
        _activeWorkspaceLocation.value = location
        _workspaceAuthority.value =
            WorkspaceAuthority(workspaceId = location.raw, generation = activateCount.toLong())
        _readiness.value = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 1uL)
    }

    override suspend fun clearWorkspace() {
        _activeWorkspaceLocation.value = null
        _workspaceAuthority.value = null
        _readiness.value = EngineReadiness.AwaitingWorkspaceSelection
    }
}
