package com.lomo.domain.usecase

import com.lomo.domain.repository.MediaRepository
import javax.inject.Inject

class SaveImageUseCase
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
    ) {
        suspend operator fun invoke(sourceUri: String): String {
            val path = mediaRepository.saveImage(sourceUri)
            mediaRepository.syncImageCache()
            return path
        }
    }
