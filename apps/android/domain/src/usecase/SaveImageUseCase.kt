package com.lomo.domain.usecase

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository

sealed interface SaveImageResult {
    val location: StorageLocation

    data class SavedAndCacheSynced(
        override val location: StorageLocation,
    ) : SaveImageResult
}

open class SaveImageUseCase
(
        private val mediaRepository: MediaRepository,
    ) {
        open suspend fun saveWithCacheSyncStatus(
            source: StorageLocation,
            draftId: DraftId,
        ): SaveImageResult =
            SaveImageResult.SavedAndCacheSynced(mediaRepository.importImage(source, draftId))
    }
