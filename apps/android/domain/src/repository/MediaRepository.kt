package com.lomo.domain.repository

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for media asset lifecycle operations.
 */
interface MediaRepository {
    suspend fun importImage(
        source: StorageLocation,
        draftId: DraftId,
    ): StorageLocation

    suspend fun removeImage(
        entryId: MediaEntryId,
        draftId: DraftId,
    )

    fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>>

    suspend fun refreshImageLocations()

    suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation?

    suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation

    /**
     * D4 voice finalize: stop-side path only stages+verifies via Rust finalizeRecording.
     * Holds staged facts for memo save promote under the same operation-id.
     * Returns the markdown destination (suggested final relative path or basename).
     * Never promotes; never journals sync.
     */
    suspend fun finalizeVoiceCapture(
        recordingLocation: StorageLocation,
        humanNameHint: String,
        draftId: DraftId,
    ): StorageLocation

    suspend fun removeVoiceCapture(
        entryId: MediaEntryId,
        captureLocation: StorageLocation,
        draftId: DraftId,
    )

    /**
     * Deterministic D6 orphan reclaim at an operation/maintenance boundary.
     * Implementations must either complete the sweep or surface its failure.
     */
    suspend fun runOrphanSweepAtOperationBoundary()
}
