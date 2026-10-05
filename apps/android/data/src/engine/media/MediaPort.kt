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

data class MediaCommittedEntry(
    val digest: String,
    val absolutePath: String,
    val size: Long,
    val modifiedMs: Long,
)

data class MediaManifest(
    val stageDirName: String,
    val entries: List<MediaCommittedEntry>,
)

data class MediaTrashEntry(
    val digest: String,
    val trashPath: String,
    val trashedAtMs: Long,
    val expiresAtMs: Long,
)

/** Why one media candidate survived the session-owned sweep. */
data class MediaSweepProtection(
    val relativePath: String,
    /** `current` | `trash` | `history` | `draft` | `pending_operation` | `stage_lease` */
    val source: String,
    val ownerKey: String,
)

/** One candidate or trash entry the sweep refused to touch. */
data class MediaSweepFailure(
    val relativePath: String,
    val code: String,
    val message: String,
)

/**
 * A host editor draft body whose attachments must be protected for one sweep. DataStore drafts
 * never enter the Rust draft store, so the sweep projects each supplied body inside its
 * transaction lock exactly like an internal draft; nothing is persisted. A body that fails
 * projection aborts the sweep rather than silently dropping its protection.
 */
data class MediaSweepDraftGuard(
    /** Opaque owner identity for diagnostics (the durable draft id). */
    val ownerId: String,
    val content: String,
)

/**
 * Observable result of the Rust session-owned two-phase media orphan sweep. The protection set is
 * recomputed inside the Rust transaction lock before any move/delete, so this report is the
 * complete record: candidates, protections, moves, purges, and per-candidate failures.
 */
data class MediaSweepReport(
    val candidates: Long,
    val protections: List<MediaSweepProtection>,
    val movedToTrash: List<MediaTrashEntry>,
    val permanentlyDeletedDigests: List<String>,
    val keptLive: Long,
    val failures: List<MediaSweepFailure>,
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
     * Walks the committed media tree. `verifiedEntries` are facts from the previous manifest this
     * host already holds: Rust reuses their digest only while path, size and mtime still match, so
     * passing stale entries is safe — a stat mismatch always rehashes.
     */
    fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<MediaCommittedEntry>,
    ): MediaManifest

    /**
     * Session-owned two-phase orphan sweep. The Rust session enumerates `media/` candidates,
     * recomputes the full protection set (live/trash bodies, in-window history, drafts, pending
     * transactions, stage leases) inside the transaction lock, then moves unreferenced objects to
     * media-trash and purges expired entries. Kotlin supplies only [externalDrafts]: the bodies
     * of editor drafts that live outside the Rust draft store and whose references must guard
     * this sweep.
     */
    fun sessionMediaOrphanSweep(
        nowMs: Long?,
        recoveryWindowMs: Long,
        externalDrafts: List<MediaSweepDraftGuard>,
    ): MediaSweepReport
}
