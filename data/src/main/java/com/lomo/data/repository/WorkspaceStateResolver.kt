package com.lomo.data.repository

import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository

interface WorkspaceStateResolver {
    suspend fun rebuildFromCurrentWorkspace()
}

object NoOpWorkspaceStateResolver : WorkspaceStateResolver {
    override suspend fun rebuildFromCurrentWorkspace() = Unit
}

class RefreshingWorkspaceStateResolver
    constructor(
        private val cleanupRepository: WorkspaceTransitionRepository,
        private val mediaRepository: MediaRepository,
        private val refreshEngine: MemoRefreshEngine,
    ) : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {
            cleanupRepository.clearMemoStateAfterWorkspaceTransition()
            refreshEngine.refresh()
            mediaRepository.refreshImageLocations()
        }
    }
