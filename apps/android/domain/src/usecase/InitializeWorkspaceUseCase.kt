package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository

class InitializeWorkspaceUseCase(private val directorySettingsRepository: DirectorySettingsRepository) {
    suspend fun currentRootLocation(): StorageLocation? = directorySettingsRepository.currentRootLocation()
}
