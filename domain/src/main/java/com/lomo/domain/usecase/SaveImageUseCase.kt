package com.lomo.domain.usecase

import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

sealed interface SaveImageResult {
    val path: String

    data class SavedAndCacheSynced(
        override val path: String,
    ) : SaveImageResult

    data class SavedButCacheSyncFailed(
        override val path: String,
        val cause: Throwable,
    ) : SaveImageResult
}

class SaveImageUseCase
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
    ) {
        suspend fun saveWithCacheSyncStatus(sourceUri: String): SaveImageResult {
            val path = mediaRepository.saveImage(sourceUri)
            return try {
                mediaRepository.syncImageCache()
                SaveImageResult.SavedAndCacheSynced(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SaveImageResult.SavedButCacheSyncFailed(path = path, cause = e)
            }
        }

        @Deprecated(
            message = "Use saveWithCacheSyncStatus for explicit non-atomic save/cache-sync outcome.",
            replaceWith = ReplaceWith("saveWithCacheSyncStatus(sourceUri)"),
        )
        suspend operator fun invoke(sourceUri: String): String {
            return when (val result = saveWithCacheSyncStatus(sourceUri)) {
                is SaveImageResult.SavedAndCacheSynced -> result.path
                is SaveImageResult.SavedButCacheSyncFailed -> throw result.cause
            }
        }
    }
