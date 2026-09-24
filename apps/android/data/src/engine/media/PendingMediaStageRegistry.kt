package com.lomo.data.engine.media

import com.lomo.domain.model.DraftId

/**
 * Draft-scoped staged-media view over the Rust durable stage ledger.
 *
 * The Rust media owner holds the authoritative artifact/lease records; this class is only the
 * Kotlin-side protocol adapter. Staged bytes stay alive while any holder still leases them, so a
 * memo operation committing one draft's media never destroys bytes another draft still needs.
 *
 * Identities are artifact ids (content digests), never basenames. A destination only selects which
 * record a discarding draft is referring to; it is never the storage identity.
 */
class PendingMediaStageRegistry(
    private val mediaPort: MediaPort,
    private val stageRoot: () -> String,
) {
    /**
     * Records a freshly staged artifact against the importing draft.
     * Returns the owner-resolved, collision-free destination the caller must embed in markdown.
     */
    fun record(
        staged: MediaStagedFacts,
        draftId: DraftId,
        workspaceRoot: String?,
    ): MediaStageRecord =
        mediaPort.recordStageLease(
            workspaceRoot = workspaceRoot,
            staged = staged,
            ownerKind = MediaStageOwnerKind.Draft,
            ownerId = draftId.value,
        )

    /**
     * Transfers this draft's claims to the frozen operation and returns the promote plans.
     *
     * Records already transferred to the same operation are included, so a retried submit keeps
     * the identical frozen plans instead of losing them.
     */
    fun plansForOperation(
        operationId: String,
        draftId: DraftId,
    ): List<MediaPromotePlan> {
        require(operationId.isNotBlank()) { "media promote operationId must be non-blank" }
        val root = stageRoot()
        val byArtifact = LinkedHashMap<String, MediaStageRecord>()
        mediaPort
            .stageRecordsForOwner(root, MediaStageOwnerKind.Draft, draftId.value)
            .forEach { byArtifact[it.artifactId] = it }
        mediaPort
            .stageRecordsForOwner(root, MediaStageOwnerKind.PendingOperation, operationId)
            .forEach { byArtifact.putIfAbsent(it.artifactId, it) }
        return byArtifact.values.map { record ->
            mediaPort.transferStageLease(
                mediaRoot = root,
                from = MediaStageLease(record.artifactId, MediaStageOwnerKind.Draft, draftId.value),
                to = MediaStageLease(record.artifactId, MediaStageOwnerKind.PendingOperation, operationId),
            )
            MediaPromotePlan(
                operationId = operationId,
                staged = record.toStagedFacts(),
                finalRelativePath = record.suggestedFinalRelativePath,
            )
        }
    }

    /** Stages a caller-owned temp file (inbox drop) under the shared media stage root. */
    fun stageIncoming(
        tempPath: String,
        humanNameHint: String,
    ): MediaStagedFacts =
        mediaPort.stageMedia(
            mediaRoot = stageRoot(),
            sourceKind = MediaSourceKind.StagedTemp,
            sourcePath = tempPath,
            humanNameHint = humanNameHint,
        )

    /**
     * Records a freshly staged artifact against an incoming transfer (sync inbox drop). The file's
     * staged bytes stay alive under this owner until its import commits or the drop is discarded.
     */
    fun recordIncoming(
        staged: MediaStagedFacts,
        ownerId: String,
        workspaceRoot: String?,
    ): MediaStageRecord =
        mediaPort.recordStageLease(
            workspaceRoot = workspaceRoot,
            staged = staged,
            ownerKind = MediaStageOwnerKind.IncomingTransfer,
            ownerId = ownerId,
        )

    /**
     * Transfers an incoming transfer's claims to the frozen operation and returns the promote plans.
     * Mirrors [plansForOperation]: records already transferred to the same operation are included,
     * so a retried resolution keeps the identical frozen plans.
     */
    fun plansForIncomingOperation(
        operationId: String,
        ownerId: String,
    ): List<MediaPromotePlan> {
        require(operationId.isNotBlank()) { "media promote operationId must be non-blank" }
        val root = stageRoot()
        val byArtifact = LinkedHashMap<String, MediaStageRecord>()
        mediaPort
            .stageRecordsForOwner(root, MediaStageOwnerKind.IncomingTransfer, ownerId)
            .forEach { byArtifact[it.artifactId] = it }
        mediaPort
            .stageRecordsForOwner(root, MediaStageOwnerKind.PendingOperation, operationId)
            .forEach { byArtifact.putIfAbsent(it.artifactId, it) }
        return byArtifact.values.map { record ->
            mediaPort.transferStageLease(
                mediaRoot = root,
                from =
                    MediaStageLease(
                        record.artifactId,
                        MediaStageOwnerKind.IncomingTransfer,
                        ownerId,
                    ),
                to =
                    MediaStageLease(
                        record.artifactId,
                        MediaStageOwnerKind.PendingOperation,
                        operationId,
                    ),
            )
            MediaPromotePlan(
                operationId = operationId,
                staged = record.toStagedFacts(),
                finalRelativePath = record.suggestedFinalRelativePath,
            )
        }
    }

    /** Releases every incoming-transfer claim of one drop (keep-local / discarded import). */
    fun releaseIncoming(ownerId: String) {
        val root = stageRoot()
        mediaPort
            .stageRecordsForOwner(root, MediaStageOwnerKind.IncomingTransfer, ownerId)
            .forEach { record ->
                mediaPort.releaseStageLease(
                    mediaRoot = root,
                    lease =
                        MediaStageLease(
                            record.artifactId,
                            MediaStageOwnerKind.IncomingTransfer,
                            ownerId,
                        ),
                )
            }
    }

    /**
     * Releases one draft's claim on the record matching [destinationKey] (full relative path or
     * basename). Returns the resolved destination when a draft-owned record matched, else null.
     */
    fun releaseDraftDestination(
        draftId: DraftId,
        destinationKey: String,
    ): String? {
        val key = destinationKey.trim()
        if (key.isEmpty()) return null
        val root = stageRoot()
        val match =
            mediaPort
                .stageRecordsForOwner(root, MediaStageOwnerKind.Draft, draftId.value)
                .firstOrNull { record ->
                    record.suggestedFinalRelativePath == key ||
                        record.suggestedFinalRelativePath.substringAfterLast('/') == key
                } ?: return null
        mediaPort.releaseStageLease(
            mediaRoot = root,
            lease = MediaStageLease(match.artifactId, MediaStageOwnerKind.Draft, draftId.value),
        )
        return match.suggestedFinalRelativePath
    }
}
