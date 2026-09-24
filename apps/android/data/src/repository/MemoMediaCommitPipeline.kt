package com.lomo.data.repository

import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.domain.model.DraftId

/**
 * The staged-media commit pipeline as one unit: freeze a draft's pending stage records into
 * promote plans before the mutation, then publish the same plans once the commit lands. Plans
 * and their publication must always pair, so mutation code holds this pipeline instead of the
 * two raw collaborators.
 */
internal class MemoMediaCommitPipeline(
    private val pendingStages: PendingMediaStageRegistry,
    private val committedMediaSink: CommittedMediaLocationSink,
) {
    fun plansForOperation(
        operationId: String,
        draftId: DraftId,
    ): List<MediaPromotePlan> = pendingStages.plansForOperation(operationId, draftId)

    fun publishCommittedMedia(plans: List<MediaPromotePlan>) =
        committedMediaSink.publishCommittedMedia(plans)
}
