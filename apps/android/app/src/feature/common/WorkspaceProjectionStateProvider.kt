package com.lomo.app.feature.common

import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Publishes the canonical app-layer admission gate for reads from the active projection. */
class WorkspaceProjectionStateProvider(
    engineReadinessRepository: EngineReadinessRepository,
) {
    val projectionReadable: Flow<Boolean> =
        engineReadinessRepository.mount
            .map { it.admitsProjectionReads }
            .distinctUntilChanged()
}
