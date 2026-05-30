package com.lomo.data.repository

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream

/*
 * Behavior Contract:
 * - Unit under test: data repository test media collaborators.
 * - Owning layer: data test infrastructure.
 * - Priority tier: P1.
 * - Capability: mutation lifecycle tests must either fail on unexpected media repository work
 *   or assert the observed media refresh handoff explicitly.
 *
 * Scenarios:
 * - Given a mutation test that does not include media behavior, when the pipeline calls media
 *   repository methods unexpectedly, then the test fails at that boundary.
 * - Given a version-restore outbox flush test, when restore completion refreshes media locations,
 *   then the collaborator records that observable handoff.
 *
 * Observable outcomes:
 * - thrown unexpected-call failures or recorded refresh counts.
 *
 * TDD proof:
 * - RED: compile failed after the lifecycle restore owner started requiring a MediaRepository
 *   collaborator in MemoMutationRuntime.
 *
 * Excludes:
 * - production media import/delete implementation and Android storage backends.
 */
internal object ThrowingMediaRepository : MediaRepository {
    override suspend fun importImage(source: StorageLocation): StorageLocation =
        unexpected("importImage")

    override suspend fun removeImage(entryId: MediaEntryId) {
        unexpected("removeImage")
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> =
        unexpected("observeImageLocations")

    override suspend fun refreshImageLocations() {
        unexpected("refreshImageLocations")
    }

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? =
        unexpected("ensureCategoryWorkspace")

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        unexpected("allocateVoiceCaptureTarget")

    override suspend fun removeVoiceCapture(entryId: MediaEntryId) {
        unexpected("removeVoiceCapture")
    }
}

internal object ThrowingWorkspaceMediaAccess : WorkspaceMediaAccess {
    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> =
        unexpected("WorkspaceMediaAccess.listFiles")

    override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> =
        unexpected("WorkspaceMediaAccess.listFilenames")

    override suspend fun readFileToStream(
        category: WorkspaceMediaCategory,
        filename: String,
        destination: OutputStream,
    ): Boolean =
        unexpected("WorkspaceMediaAccess.readFileToStream")

    override suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    ) {
        unexpected("WorkspaceMediaAccess.writeFileFromStream")
    }

    override suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    ) {
        unexpected("WorkspaceMediaAccess.deleteFile")
    }
}

internal class RecordingMediaRepository : MediaRepository {
    private val locations = MutableStateFlow<Map<MediaEntryId, StorageLocation>>(emptyMap())

    var refreshImageLocationsCallCount: Int = 0
        private set

    override suspend fun importImage(source: StorageLocation): StorageLocation = source

    override suspend fun removeImage(entryId: MediaEntryId) {
        locations.value = locations.value - entryId
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = locations.asStateFlow()

    override suspend fun refreshImageLocations() {
        refreshImageLocationsCallCount += 1
    }

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = null

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        StorageLocation(entryId.raw)

    override suspend fun removeVoiceCapture(entryId: MediaEntryId) = Unit
}

private fun unexpected(method: String): Nothing =
    error("Unexpected MediaRepository.$method call in this memo lifecycle test")
