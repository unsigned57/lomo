package com.lomo.data.testing.fakes

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-test readiness collaborator for data-layer write-gate contracts. */
class FakeEngineReadinessRepository(
    initial: EngineReadiness = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 0uL),
) : EngineReadinessRepository {
    private val _readiness = MutableStateFlow(initial)
    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()

    fun publish(value: EngineReadiness) {
        _readiness.value = value
    }

    override fun resnapshot() = Unit

    override suspend fun activateWorkspace(location: StorageLocation) {
        _readiness.value = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 1uL)
    }

    override suspend fun clearWorkspace() {
        _readiness.value = EngineReadiness.AwaitingWorkspaceSelection
    }
}
