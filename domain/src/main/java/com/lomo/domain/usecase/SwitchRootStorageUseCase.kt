package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository

class SwitchRootStorageUseCase
    constructor(
        private val directorySettingsRepository: DirectorySettingsRepository,
        private val cleanupRepository: WorkspaceTransitionRepository,
    ) {
        suspend fun updateRootLocation(location: StorageLocation) {
            directorySettingsRepository.applyRootLocation(location)
            cleanupRepository.clearMemoStateAfterWorkspaceTransition()
        }
    }
