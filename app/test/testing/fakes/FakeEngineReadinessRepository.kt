package com.lomo.app.testing.fakes

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
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
    var lastActivated: StorageLocation? = null
        private set

    fun publish(value: EngineReadiness) {
        _readiness.value = value
    }

    override fun resnapshot() = Unit

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
