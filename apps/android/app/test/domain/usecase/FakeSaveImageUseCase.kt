package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository

class FakeSaveImageUseCase(
    private val mediaRepository: MediaRepository
) : SaveImageUseCase(mediaRepository) {
    var saveResult: SaveImageResult? = null
    var saveException: Throwable? = null

    override suspend fun saveWithCacheSyncStatus(
        source: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ): SaveImageResult {
        saveException?.let { throw it }
        return saveResult ?: super.saveWithCacheSyncStatus(source, draftId)
    }
}
