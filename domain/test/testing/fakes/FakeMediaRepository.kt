package com.lomo.domain.testing.fakes

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeMediaRepository : MediaRepository {
    private val imageLocations = MutableStateFlow<Map<MediaEntryId, StorageLocation>>(emptyMap())
    private val categoryWorkspaceResults = mutableMapOf<MediaCategory, StorageLocation?>()
    private val voiceTargets = mutableMapOf<MediaEntryId, StorageLocation>()

    val importedSources = mutableListOf<StorageLocation>()
    val removedImageIds = mutableListOf<MediaEntryId>()
    val ensuredCategories = mutableListOf<MediaCategory>()
    val removedVoiceCaptureIds = mutableListOf<MediaEntryId>()

    var nextImportResult: StorageLocation = StorageLocation("/images/imported.jpg")
    var importFailure: Exception? = null
    var refreshImageLocationsCallCount = 0
        private set

    fun setImageLocations(value: Map<MediaEntryId, StorageLocation>) {
        imageLocations.value = value
    }

    fun setCategoryWorkspaceResult(
        category: MediaCategory,
        location: StorageLocation?,
    ) {
        categoryWorkspaceResults[category] = location
    }

    fun setVoiceTarget(
        entryId: MediaEntryId,
        location: StorageLocation,
    ) {
        voiceTargets[entryId] = location
    }

    override suspend fun importImage(source: StorageLocation): StorageLocation {
        importFailure?.let { throw it }
        importedSources += source
        return nextImportResult
    }

    override suspend fun removeImage(entryId: MediaEntryId) {
        removedImageIds += entryId
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = imageLocations.asStateFlow()

    override suspend fun refreshImageLocations() {
        refreshImageLocationsCallCount += 1
    }

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? {
        ensuredCategories += category
        return categoryWorkspaceResults[category]
    }

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        voiceTargets[entryId] ?: StorageLocation("/voice/${entryId.raw}.m4a")

    override suspend fun removeVoiceCapture(entryId: MediaEntryId) {
        removedVoiceCaptureIds += entryId
    }
}
