package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository

class FakeSaveImageUseCase(
    private val mediaRepository: MediaRepository
) : SaveImageUseCase(mediaRepository) {
    var saveResult: SaveImageResult? = null

    override suspend fun saveWithCacheSyncStatus(source: StorageLocation): SaveImageResult {
        return saveResult ?: super.saveWithCacheSyncStatus(source)
    }
}
