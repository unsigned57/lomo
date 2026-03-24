package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository

sealed interface SaveImageResult {
    val location: StorageLocation

    data class SavedAndCacheSynced(
        override val location: StorageLocation,
    ) : SaveImageResult

    data class SavedButCacheSyncFailed(
        override val location: StorageLocation,
        val cause: Throwable,
    ) : SaveImageResult
}

class SaveImageUseCase
(
        private val mediaRepository: MediaRepository,
    ) {
        suspend fun saveWithCacheSyncStatus(source: StorageLocation): SaveImageResult =
            SaveImageResult.SavedAndCacheSynced(mediaRepository.importImage(source))
    }
