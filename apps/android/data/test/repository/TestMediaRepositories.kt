package com.lomo.data.repository

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.MediaImageDescriptor
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
    override suspend fun importImage(
        source: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation = unexpected("importImage")

    override suspend fun removeImage(
        entryId: MediaEntryId,
        draftId: com.lomo.domain.model.DraftId,
    ) {
        unexpected("removeImage")
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, MediaImageDescriptor>> =
        unexpected("observeImageLocations")

    override suspend fun refreshImageLocations() {
        unexpected("refreshImageLocations")
    }

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? =
        unexpected("ensureCategoryWorkspace")

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        unexpected("allocateVoiceCaptureTarget")

    override suspend fun finalizeVoiceCapture(
        recordingLocation: StorageLocation,
        humanNameHint: String,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation = unexpected("finalizeVoiceCapture")

    override suspend fun removeVoiceCapture(
        entryId: MediaEntryId,
        captureLocation: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ) {
        unexpected("removeVoiceCapture")
    }

    override suspend fun runOrphanSweepAtOperationBoundary() {
        unexpected("runOrphanSweepAtOperationBoundary")
    }
}

internal class RecordingCommittedMediaLocationSink : CommittedMediaLocationSink {
    val published = mutableListOf<com.lomo.data.engine.media.MediaPromotePlan>()

    override fun publishCommittedMedia(plans: List<com.lomo.data.engine.media.MediaPromotePlan>) {
        published += plans
    }
}

internal class RecordingMediaRepository : MediaRepository {
    private val locations = MutableStateFlow<Map<MediaEntryId, MediaImageDescriptor>>(emptyMap())

    var refreshImageLocationsCallCount: Int = 0
        private set

    var finalizeCallCount: Int = 0
        private set

    var orphanSweepCallCount: Int = 0
        private set

    override suspend fun importImage(
        source: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation = source

    override suspend fun removeImage(
        entryId: MediaEntryId,
        draftId: com.lomo.domain.model.DraftId,
    ) {
        locations.value = locations.value - entryId
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, MediaImageDescriptor>> =
        locations.asStateFlow()

    override suspend fun refreshImageLocations() {
        refreshImageLocationsCallCount += 1
    }

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = null

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        StorageLocation("file:///tmp/stage/${entryId.raw}")

    override suspend fun finalizeVoiceCapture(
        recordingLocation: StorageLocation,
        humanNameHint: String,
        draftId: com.lomo.domain.model.DraftId,
    ): StorageLocation {
        finalizeCallCount += 1
        val name = humanNameHint.ifBlank { "voice.m4a" }
        return StorageLocation("media/$name")
    }

    override suspend fun removeVoiceCapture(
        entryId: MediaEntryId,
        captureLocation: StorageLocation,
        draftId: com.lomo.domain.model.DraftId,
    ) = Unit

    override suspend fun runOrphanSweepAtOperationBoundary() {
        orphanSweepCallCount += 1
    }
}

private fun unexpected(method: String): Nothing =
    error("Unexpected MediaRepository.$method call in this memo lifecycle test")

/**
 * MediaPort with no staged artifacts. Stage-lease queries return nothing and releases are no-ops,
 * which is the correct empty-ledger state for memo lifecycle tests that never import media.
 */
internal class NoOpMediaPort : com.lomo.data.engine.media.MediaPort {
    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.data.engine.media.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.data.engine.media.MediaStagedFacts = error("stageMedia is not expected")

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: com.lomo.data.engine.media.MediaStagedFacts,
        ownerKind: com.lomo.data.engine.media.MediaStageOwnerKind,
        ownerId: String,
    ): com.lomo.data.engine.media.MediaStageRecord = error("recordStageLease is not expected")

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: com.lomo.data.engine.media.MediaStageOwnerKind,
        ownerId: String,
    ): List<com.lomo.data.engine.media.MediaStageRecord> = emptyList()

    override fun transferStageLease(
        mediaRoot: String,
        from: com.lomo.data.engine.media.MediaStageLease,
        to: com.lomo.data.engine.media.MediaStageLease,
    ): com.lomo.data.engine.media.MediaStageRelease = error("transferStageLease is not expected")

    override fun releaseStageLease(
        mediaRoot: String,
        lease: com.lomo.data.engine.media.MediaStageLease,
    ): com.lomo.data.engine.media.MediaStageRelease = error("releaseStageLease is not expected")

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = error("allocateRecordingTarget is not expected")

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): com.lomo.data.engine.media.MediaStagedFacts = error("finalizeRecording is not expected")

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<com.lomo.data.engine.media.MediaCommittedEntry>,
    ): com.lomo.data.engine.media.MediaManifest =
        error("queryMediaManifest is not expected")

    override fun sessionMediaOrphanSweep(
        nowMs: Long?,
        recoveryWindowMs: Long,
    ): com.lomo.data.engine.media.MediaSweepReport = error("sessionMediaOrphanSweep is not expected")
}
