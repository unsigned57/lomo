package com.lomo.domain.testing.fakes

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeEngineReadinessRepository(
    initial: EngineReadiness = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 0uL),
) : EngineReadinessRepository {
    private val _readiness = MutableStateFlow(initial)
    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
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

    override suspend fun activateWorkspace(location: StorageLocation) {
        activateCount += 1
        lastActivated = location
        if (remainingActivateFailures > 0) {
            remainingActivateFailures -= 1
            throw activateFailure
        }
        _readiness.value =
            activateResult
                ?: EngineReadiness.Ready(coreRevision = 0uL, eventSequence = activateCount.toULong())
    }

    override suspend fun clearWorkspace() {
        clearCount += 1
        _readiness.value = EngineReadiness.AwaitingWorkspaceSelection
        lastActivated = null
    }
}
