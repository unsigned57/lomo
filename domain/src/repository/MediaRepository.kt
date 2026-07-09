package com.lomo.domain.repository

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for media asset lifecycle operations.
 */
interface MediaRepository {
    suspend fun importImage(source: StorageLocation): StorageLocation

    suspend fun removeImage(entryId: MediaEntryId)

    fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>>

    suspend fun refreshImageLocations()

    suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation?

    suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation

    suspend fun removeVoiceCapture(entryId: MediaEntryId)
}
