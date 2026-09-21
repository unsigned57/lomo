package com.lomo.data.engine.media

/**
 * Production media surface (P4-10A) over BoltFFI path-only media commands.
 *
 * Sole media identity / stage / promote / manifest / orphan authority is Rust
 * media owner via native path-only commands. Kotlin supplies filesystem paths and Android URI/temp only.
 * No full media bytes cross this boundary.
 */
data class MediaStagedFacts(
    val digest: String,
    val size: Long,
    val mime: String,
    val stagingPath: String,
    val humanNameHint: String,
    /** Rust owner-suggested final relative path (`media/...`); hosts must not invent digests basenames. */
    val suggestedFinalRelativePath: String,
)

data class MediaPromotePlan(
    val operationId: String,
    val staged: MediaStagedFacts,
    val finalRelativePath: String,
)

/** Who currently holds staged bytes; mirrors the Rust stage-ledger owner vocabulary. */
enum class MediaStageOwnerKind {
    Draft,
    PendingOperation,
    IncomingTransfer,
    CommittedReference,
}

data class MediaStageLease(
    val artifactId: String,
    val ownerKind: MediaStageOwnerKind,
    val ownerId: String,
)

/**
 * Durable staged-artifact record. Kotlin holds no authoritative copy; the Rust ledger owns it and
 * this is a snapshot returned across the boundary.
 */
data class MediaStageRecord(
    val artifactId: String,
    val digest: String,
    val size: Long,
    val mime: String,
    val stagingPath: String,
    val humanNameHint: String,
    val suggestedFinalRelativePath: String,
    val leases: List<MediaStageLease>,
    /** False when the staged bytes vanished; the draft must surface a recoverable failure. */
    val stagedBytesPresent: Boolean,
) {
    fun toStagedFacts(): MediaStagedFacts =
        MediaStagedFacts(
            digest = digest,
            size = size,
            mime = mime,
            stagingPath = stagingPath,
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = suggestedFinalRelativePath,
        )
}

data class MediaStageRelease(
    val artifactId: String,
    val remainingLeases: Long,
    val bytesDeleted: Boolean,
)

data class MediaPromoteResult(
    val operationId: String,
    val digest: String,
    val mime: String,
    val size: Long,
    val finalAbsolutePath: String,
    val finalRelativePath: String,
)

data class MediaCommittedEntry(
    val digest: String,
    val absolutePath: String,
)

data class MediaManifest(
    val stageDirName: String,
    val entries: List<MediaCommittedEntry>,
)

data class MediaAttachmentRef(
    val digest: String,
    val ownerKey: String,
    /** `current` | `trash` | `history` */
    val source: String,
)

data class MediaTrashEntry(
    val digest: String,
    val trashPath: String,
    val trashedAtMs: Long,
    val expiresAtMs: Long,
)

data class MediaOrphanSweepResult(
    val movedToTrash: List<MediaTrashEntry>,
    val permanentlyDeletedDigests: List<String>,
    val keptLive: Long,
)

enum class MediaSourceKind {
    DirectPath,
    StagedTemp,
}

interface MediaPort {
    fun stageMedia(
        mediaRoot: String,
        sourceKind: MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): MediaStagedFacts

    /**
     * Records a freshly staged artifact in the durable stage ledger and acquires one owner lease.
     * Returns the record with the collision-free destination the owner resolved.
     */
    fun recordStageLease(
        workspaceRoot: String?,
        staged: MediaStagedFacts,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): MediaStageRecord

    fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: MediaStageOwnerKind,
        ownerId: String,
    ): List<MediaStageRecord>

    /** Hands one holder's claim to another; never deletes bytes. */
    fun transferStageLease(
        mediaRoot: String,
        from: MediaStageLease,
        to: MediaStageLease,
    ): MediaStageRelease

    /** Releases one lease; staged bytes are deleted only when no lease remains. */
    fun releaseStageLease(
        mediaRoot: String,
        lease: MediaStageLease,
    ): MediaStageRelease

    fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String

    fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): MediaStagedFacts

    /**
     * Recovery / dark-surface promote. Production import must not call this: memo save promotes
     * via [com.lomo.data.engine.store.StoreMemoCommand.pendingPromotes] under the same operation-id.
     */
    fun promoteMedia(
        workspaceRoot: String,
        plan: MediaPromotePlan,
    ): MediaPromoteResult

    fun queryMediaManifest(workspaceRoot: String): MediaManifest

    fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<MediaCommittedEntry>,
        refs: List<MediaAttachmentRef>,
        existingTrash: List<MediaTrashEntry>,
        nowMs: Long?,
        recoveryWindowMs: Long,
    ): MediaOrphanSweepResult
}
