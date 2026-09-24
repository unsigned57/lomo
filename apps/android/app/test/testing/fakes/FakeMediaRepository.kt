package com.lomo.app.testing.fakes

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.MediaImageDescriptor
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeMediaRepository : MediaRepository {
    private val _imageLocations = MutableStateFlow<Map<MediaEntryId, MediaImageDescriptor>>(emptyMap())

    fun setImageLocations(locations: Map<MediaEntryId, StorageLocation>) {
        _imageLocations.value =
            locations.mapValues { (_, location) ->
                MediaImageDescriptor(location = location, contentId = null)
            }
    }

    fun setImageDescriptors(descriptors: Map<MediaEntryId, MediaImageDescriptor>) {
        _imageLocations.value = descriptors
    }

    override suspend fun importImage(
        source: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation {
        return source
    }

    override suspend fun removeImage(
        entryId: MediaEntryId,
        draftId: com.lomo.domain.model.DraftId,
    ) {
        val current = _imageLocations.value.toMutableMap()
        current.remove(entryId)
        _imageLocations.value = current
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, MediaImageDescriptor>> =
        _imageLocations.asStateFlow()

    var refreshImageLocationsCallCount = 0
        private set

    override suspend fun refreshImageLocations() {
        refreshImageLocationsCallCount++
        finishRefresh?.await()
    }

    var ensureCategoryWorkspaceResult: StorageLocation? = null
    var ensureCategoryWorkspaceFailure: Throwable? = null

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? {
        ensureCategoryWorkspaceFailure?.let { throw it }
        return ensureCategoryWorkspaceResult
    }

    fun verifyRefreshImageLocationsCalled(exactly: Int = 1) {
        if (refreshImageLocationsCallCount != exactly) {
            throw AssertionError("Expected refreshImageLocations to be called $exactly times, but was called $refreshImageLocationsCallCount times")
        }
    }

    fun verifyRefreshImageLocationsNotCalled() {
        if (refreshImageLocationsCallCount != 0) {
            throw AssertionError("Expected refreshImageLocations not to be called, but was called $refreshImageLocationsCallCount times")
        }
    }

    private var finishRefresh: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    fun setFinishRefresh(deferred: kotlinx.coroutines.CompletableDeferred<Unit>) {
        finishRefresh = deferred
    }

    fun resetRecordedCalls() {
        refreshImageLocationsCallCount = 0
    }

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation = StorageLocation("")

    override suspend fun finalizeVoiceCapture(
        recordingLocation: StorageLocation,
        humanNameHint: String,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation = StorageLocation(humanNameHint.ifBlank { "voice.m4a" })

    override suspend fun removeVoiceCapture(
        entryId: MediaEntryId,
        captureLocation: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ) {}

    override suspend fun runOrphanSweepAtOperationBoundary() {}
}
