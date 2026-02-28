package com.lomo.domain.usecase

import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import javax.inject.Inject

class InitializeWorkspaceUseCase
    @Inject
    constructor(
        private val directorySettingsRepository: DirectorySettingsRepository,
        private val mediaRepository: MediaRepository,
    ) {
        suspend fun currentRootDirectory(): String? = directorySettingsRepository.getRootDirectoryOnce()

        suspend fun ensureDefaultMediaDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            if (forImage) {
                mediaRepository.createDefaultImageDirectory()
            }
            if (forVoice) {
                mediaRepository.createDefaultVoiceDirectory()
            }
        }
    }
