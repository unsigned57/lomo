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

    /**
     * Releases the operation's claims after a durable commit. Bytes are deleted only when no other
     * holder remains, so a shared artifact survives for the draft that still needs it.
     */
    fun releaseOperation(plans: Collection<MediaPromotePlan>) {
        if (plans.isEmpty()) return
        val root = stageRoot()
        plans.forEach { plan ->
            mediaPort.releaseStageLease(
                mediaRoot = root,
                lease =
                    MediaStageLease(
                        artifactId = plan.staged.digest,
                        ownerKind = MediaStageOwnerKind.PendingOperation,
                        ownerId = plan.operationId,
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
