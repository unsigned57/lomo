package com.lomo.domain.usecase

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Read-only workspace session facts for presentation hosts.
 *
 * The app observes readiness, the installed location and the published mount through this single
 * owner instead of depending on the engine repository, so activation and presentation consume the
 * same fact.
 */
class ObserveWorkspaceSessionUseCase(
    engineReadinessRepository: EngineReadinessRepository,
) {
    /** Single mount value: readiness, location, authority and projection freshness together. */
    val mount: StateFlow<WorkspaceMount> = engineReadinessRepository.mount

    val readiness: StateFlow<EngineReadiness> = engineReadinessRepository.readiness

    /**
     * Location of the engine currently installed at Ready, or null when no workspace is active.
     */
    val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        engineReadinessRepository.activeWorkspaceLocation

    /**
     * Resolves with the first settled readiness once the session leaves its initial
     * [EngineReadiness.Opening] state.
     */
    suspend fun awaitSettledReadiness(): EngineReadiness =
        readiness.first { state -> state !is EngineReadiness.Opening }

    /** Root path of the installed workspace, distinct across location changes. */
    fun observeActiveRootPath(): Flow<String?> =
        activeWorkspaceLocation
            .map { location -> location?.raw }
            .distinctUntilChanged()
}
