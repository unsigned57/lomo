package com.lomo.data.repository

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ready + unfrozen authority for unit tests that exercise non-authority behavior.
 * Production types must receive an explicit process [WorkspaceWriteAuthority]; they must not
 * silently default open.
 */
internal val AlwaysWritableWorkspaceWriteAuthority: WorkspaceWriteAuthority =
    WorkspaceWriteAuthority(
        engineReadinessRepository =
            object : EngineReadinessRepository {
                private val state =
                    MutableStateFlow<EngineReadiness>(
                        EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 0uL),
                    )
                private val activeLocation = MutableStateFlow<StorageLocation?>(null)

                override val readiness: StateFlow<EngineReadiness> = state.asStateFlow()
                override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
                    activeLocation.asStateFlow()

                override fun resnapshot() = Unit

                override suspend fun activateWorkspace(location: StorageLocation) {
                    activeLocation.value = location
                    state.value = EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 1uL)
                }

                override suspend fun clearWorkspace() {
                    activeLocation.value = null
                    state.value = EngineReadiness.AwaitingWorkspaceSelection
                }
            },
        writeFreezeRepository = ProcessWriteFreezeRepository(),
    )
