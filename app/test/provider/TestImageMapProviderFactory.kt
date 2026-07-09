package com.lomo.app.provider

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

fun emptyImageMapProvider(
    repository: MediaRepository = FakeMediaRepository(),
    locationsFlow: Flow<Map<MediaEntryId, StorageLocation>> = flowOf(emptyMap()),
): ImageMapProvider {
    val resolvedRepository =
        if (repository is FakeMediaRepository) {
            repository.copy(imageLocations = locationsFlow)
        } else {
            repository
        }
    return ImageMapProvider(resolvedRepository)
}

private data class FakeMediaRepository(
    val imageLocations: Flow<Map<MediaEntryId, StorageLocation>> = flowOf(emptyMap()),
) : MediaRepository {
    override suspend fun importImage(source: StorageLocation): StorageLocation = source

    override suspend fun removeImage(entryId: MediaEntryId) = Unit

    override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = imageLocations

    override suspend fun refreshImageLocations() = Unit

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = null

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        StorageLocation("voice/${entryId.raw}")

    override suspend fun removeVoiceCapture(entryId: MediaEntryId) = Unit
}
