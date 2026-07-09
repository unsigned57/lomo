package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.WorkspaceStateResolver

open class SwitchRootStorageUseCase
(
        private val directorySettingsRepository: DirectorySettingsRepository,
        private val workspaceStateResolver: WorkspaceStateResolver,
    ) {
        open suspend fun updateRootLocation(location: StorageLocation) {
            directorySettingsRepository.applyRootLocation(location)
            rebuildCurrentWorkspace()
        }

        open suspend fun rebuildCurrentWorkspace() {
            workspaceStateResolver.rebuildFromCurrentWorkspace()
        }
    }
