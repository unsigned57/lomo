package com.lomo.app.provider

import com.lomo.domain.model.DraftMediaReconciliation
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.MediaImageDescriptor
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Test wiring with empty image locations by default. The local fake is copied with [locationsFlow];
 * an explicitly supplied non-fake [repository] is preserved. This helper does not verify production
 * storage refresh, import/removal or UI behavior; those belong to their consuming specifications.
 */
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
    private val descriptorLocations: Flow<Map<MediaEntryId, MediaImageDescriptor>> =
        imageLocations.map { locations ->
            locations.mapValues { (_, location) ->
                MediaImageDescriptor(location = location, contentId = null)
            }
        }
    override suspend fun importImage(
        source: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation = source

    override suspend fun removeImage(
        entryId: MediaEntryId,
        draftId: com.lomo.domain.model.DraftId,
    ) = Unit

    override fun observeImageLocations(): Flow<Map<MediaEntryId, MediaImageDescriptor>> =
        descriptorLocations

    override suspend fun refreshImageLocations() = Unit

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = null

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        StorageLocation("voice/${entryId.raw}")

    override suspend fun finalizeVoiceCapture(
        recordingLocation: StorageLocation,
        humanNameHint: String,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation = StorageLocation(humanNameHint.ifBlank { "voice.m4a" })

    override suspend fun removeVoiceCapture(
        entryId: MediaEntryId,
        captureLocation: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ) = Unit

    override suspend fun reconcileDraftMedia(
        draftId: com.lomo.domain.model.DraftId,
    ): DraftMediaReconciliation = DraftMediaReconciliation(draftId = draftId, records = emptyList())

    override suspend fun releaseDraftLeases(draftId: com.lomo.domain.model.DraftId) = Unit

    override suspend fun runOrphanSweepAtOperationBoundary() = Unit
}
