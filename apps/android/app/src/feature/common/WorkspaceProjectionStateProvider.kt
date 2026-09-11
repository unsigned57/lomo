package com.lomo.app.feature.common

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.permitsReadsAt
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Publishes the canonical app-layer admission gate for reads from the active projection. */
class WorkspaceProjectionStateProvider(
    engineReadinessRepository: EngineReadinessRepository,
) {
    val projectionReadable: Flow<Boolean> =
        combine(
            engineReadinessRepository.readiness,
            engineReadinessRepository.workspaceAuthority,
            engineReadinessRepository.projectionFreshness,
        ) { readiness, authority, freshness ->
            readiness is EngineReadiness.Ready &&
                authority != null &&
                freshness.permitsReadsAt(authority.projectionRevision)
        }.distinctUntilChanged()
}
