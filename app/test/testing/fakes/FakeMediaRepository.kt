package com.lomo.app.testing.fakes

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeMediaRepository : MediaRepository {
    private val _imageLocations = MutableStateFlow<Map<MediaEntryId, StorageLocation>>(emptyMap())

    fun setImageLocations(locations: Map<MediaEntryId, StorageLocation>) {
        _imageLocations.value = locations
    }

    override suspend fun importImage(source: StorageLocation): StorageLocation {
        return source
    }

    override suspend fun removeImage(entryId: MediaEntryId) {
        val current = _imageLocations.value.toMutableMap()
        current.remove(entryId)
        _imageLocations.value = current
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = _imageLocations.asStateFlow()

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

    override suspend fun removeVoiceCapture(entryId: MediaEntryId) {}
}
