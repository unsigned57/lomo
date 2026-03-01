package com.lomo.domain.usecase

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository

class InitializeWorkspaceUseCase
    constructor(
        private val directorySettingsRepository: DirectorySettingsRepository,
        private val mediaRepository: MediaRepository,
    ) {
        suspend fun currentRootLocation(): StorageLocation? = directorySettingsRepository.currentRootLocation()

        suspend fun ensureDefaultMediaDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            if (forImage) {
                mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE)
            }
            if (forVoice) {
                mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE)
            }
        }
    }
