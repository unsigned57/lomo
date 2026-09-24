package com.lomo.data.engine.media

import com.lomo.nativebridge.MediaCommittedEntryDto as BridgeCommitted
import com.lomo.nativebridge.MediaSourceKind as BridgeSourceKind
import com.lomo.nativebridge.MediaStageLeaseDto as BridgeStageLease
import com.lomo.nativebridge.MediaStageOwnerKindDto as BridgeStageOwnerKind
import com.lomo.nativebridge.MediaStageRecordDto as BridgeStageRecord
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged

/**
 * Production [MediaPort] over [MediaNativeBridge] (ManagedEngineSession / BoltFFI).
 *
 * Mapping only — identity/digest/mime/orphan rules stay in Rust. Memo save promotes staged
 * media through `StoreMemoCommand.pendingPromotes` under the same operation-id; there is no
 * standalone promote surface.
 */
internal class BoltFfiMediaPort(
    private val bridge: MediaNativeBridge,
) : MediaPort {
    override fun stageMedia(
        mediaRoot: String,
        sourceKind: MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): MediaStagedFacts =
        bridge
            .stageMedia(
                mediaRoot = mediaRoot,
                sourceKind = sourceKind.toBridge(),
                sourcePath = sourcePath,
                humanNameHint = humanNameHint,
            ).toFacts()

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: MediaStagedFacts,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): MediaStageRecord =
        bridge
            .recordStageLease(
                workspaceRoot = workspaceRoot,
                staged = staged.toBridge(),
                ownerKind = ownerKind.toBridge(),
                ownerId = ownerId,
            ).toRecord()

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): List<MediaStageRecord> =
        bridge
            .stageRecordsForOwner(mediaRoot, ownerKind.toBridge(), ownerId)
            .map { record -> record.toRecord() }

    override fun transferStageLease(
        mediaRoot: String,
        from: MediaStageLease,
        to: MediaStageLease,
    ): MediaStageRelease =
        bridge.transferStageLease(mediaRoot, from.toBridge(), to.toBridge()).toRelease()

    override fun releaseStageLease(
        mediaRoot: String,
        lease: MediaStageLease,
    ): MediaStageRelease = bridge.releaseStageLease(mediaRoot, lease.toBridge()).toRelease()

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = bridge.allocateRecordingTarget(mediaRoot, extension)

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): MediaStagedFacts =
        bridge
            .finalizeRecording(mediaRoot, recordingPath, humanNameHint)
            .toFacts()

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<MediaCommittedEntry>,
    ): MediaManifest {
        val manifest =
            bridge.queryMediaManifest(
                workspaceRoot,
                verifiedEntries.map { entry -> entry.toBridge() },
            )
        return MediaManifest(
            stageDirName = manifest.stageDirName,
            entries =
                manifest.entries.map { entry ->
                    MediaCommittedEntry(
                        digest = entry.digest,
                        absolutePath = entry.absolutePath,
                        size = entry.size.toLong(),
                        modifiedMs = entry.modifiedMs.toLong(),
                    )
                },
        )
    }

    override fun sessionMediaOrphanSweep(
        nowMs: Long?,
        recoveryWindowMs: Long,
    ): MediaSweepReport {
        val result =
            bridge.sessionMediaOrphanSweep(
                nowMs = nowMs?.toULong(),
                recoveryWindowMs = recoveryWindowMs.toULong(),
            )
        return MediaSweepReport(
            candidates = result.candidates.toLong(),
            protections =
                result.protections.map { protection ->
                    MediaSweepProtection(
                        relativePath = protection.relativePath,
                        source = protection.source,
                        ownerKey = protection.ownerKey,
                    )
                },
            movedToTrash =
                result.movedToTrash.map { trash ->
                    MediaTrashEntry(
                        digest = trash.digest,
                        trashPath = trash.trashPath,
                        trashedAtMs = trash.trashedAtMs.toLong(),
                        expiresAtMs = trash.expiresAtMs.toLong(),
                    )
                },
            permanentlyDeletedDigests = result.permanentlyDeletedDigests,
            keptLive = result.keptLive.toLong(),
            failures =
                result.failures.map { failure ->
                    MediaSweepFailure(
                        relativePath = failure.relativePath,
                        code = failure.code,
                        message = failure.message,
                    )
                },
        )
    }

    private fun MediaCommittedEntry.toBridge(): BridgeCommitted =
        BridgeCommitted(
            digest = digest,
            absolutePath = absolutePath,
            size = size.toULong(),
            modifiedMs = modifiedMs.toULong(),
        )

    private fun MediaSourceKind.toBridge(): BridgeSourceKind =
        when (this) {
            MediaSourceKind.DirectPath -> BridgeSourceKind.DIRECT_PATH
            MediaSourceKind.StagedTemp -> BridgeSourceKind.STAGED_TEMP
        }

    private fun BridgeStaged.toFacts(): MediaStagedFacts =
        MediaStagedFacts(
            digest = digest,
            size = size.toLong(),
            mime = mime,
            stagingPath = stagingPath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = suggestedFinalRelativePath,
        )

    private fun MediaStagedFacts.toBridge(): BridgeStaged =
        BridgeStaged(
            digest = digest,
            size = size.toULong(),
            mime = mime,
            stagingPath = stagingPath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = suggestedFinalRelativePath,
        )

    private fun MediaStageOwnerKind.toBridge(): BridgeStageOwnerKind =
        when (this) {
            MediaStageOwnerKind.Draft -> BridgeStageOwnerKind.DRAFT
            MediaStageOwnerKind.PendingOperation -> BridgeStageOwnerKind.PENDING_OPERATION
            MediaStageOwnerKind.IncomingTransfer -> BridgeStageOwnerKind.INCOMING_TRANSFER
            MediaStageOwnerKind.CommittedReference -> BridgeStageOwnerKind.COMMITTED_REFERENCE
        }

    private fun BridgeStageOwnerKind.toOwnerKind(): MediaStageOwnerKind =
        when (this) {
            BridgeStageOwnerKind.DRAFT -> MediaStageOwnerKind.Draft
            BridgeStageOwnerKind.PENDING_OPERATION -> MediaStageOwnerKind.PendingOperation
            BridgeStageOwnerKind.INCOMING_TRANSFER -> MediaStageOwnerKind.IncomingTransfer
            BridgeStageOwnerKind.COMMITTED_REFERENCE -> MediaStageOwnerKind.CommittedReference
        }

    private fun MediaStageLease.toBridge(): BridgeStageLease =
        BridgeStageLease(
            artifactId = artifactId,
            ownerKind = ownerKind.toBridge(),
            ownerId = ownerId,
        )

    private fun BridgeStageLease.toLease(): MediaStageLease =
        MediaStageLease(
            artifactId = artifactId,
            ownerKind = ownerKind.toOwnerKind(),
            ownerId = ownerId,
        )

    private fun BridgeStageRecord.toRecord(): MediaStageRecord =
        MediaStageRecord(
            artifactId = artifactId,
            digest = digest,
            size = size.toLong(),
            mime = mime,
            stagingPath = stagingPath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = suggestedFinalRelativePath,
            leases = leases.map { lease -> lease.toLease() },
            stagedBytesPresent = stagedBytesPresent,
        )

    private fun com.lomo.nativebridge.MediaStageReleaseDto.toRelease(): MediaStageRelease =
        MediaStageRelease(
            artifactId = artifactId,
            remainingLeases = remainingLeases.toLong(),
            bytesDeleted = bytesDeleted,
        )
}
