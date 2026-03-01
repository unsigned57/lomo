package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

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
    constructor(
        private val mediaRepository: MediaRepository,
    ) {
        suspend fun saveWithCacheSyncStatus(source: StorageLocation): SaveImageResult {
            val location = mediaRepository.importImage(source)
            return try {
                mediaRepository.refreshImageLocations()
                SaveImageResult.SavedAndCacheSynced(location)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SaveImageResult.SavedButCacheSyncFailed(location = location, cause = e)
            }
        }

        @Deprecated(
            message = "Use saveWithCacheSyncStatus for explicit non-atomic save/cache-sync outcome.",
            replaceWith = ReplaceWith("saveWithCacheSyncStatus(com.lomo.domain.model.StorageLocation(sourceUri))"),
        )
        suspend operator fun invoke(sourceUri: String): String {
            return when (val result = saveWithCacheSyncStatus(StorageLocation(sourceUri))) {
                is SaveImageResult.SavedAndCacheSynced -> result.location.raw
                is SaveImageResult.SavedButCacheSyncFailed -> throw result.cause
            }
        }
    }
