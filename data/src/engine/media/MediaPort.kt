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
