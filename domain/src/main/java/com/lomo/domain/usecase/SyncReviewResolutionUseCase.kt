package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession

class SyncReviewResolutionUseCase(
    private val syncProviderRegistry: SyncProviderRegistry,
) {
    suspend fun resolve(
        review: SyncReviewSession,
        resolution: SyncReviewResolution,
    ): SyncReviewResolutionResult =
        when (review.source) {
            SyncBackendType.NONE -> SyncReviewResolutionResult.Resolved
            else ->
                syncProviderRegistry
                    .get(review.source)
                    ?.resolveReview(resolution, review)
                    ?.toReviewResolutionResult()
                    ?: SyncReviewResolutionResult.Resolved
        }
}

sealed interface SyncReviewResolutionResult {
    data object Resolved : SyncReviewResolutionResult

    data class Pending(
        val review: SyncReviewSession,
    ) : SyncReviewResolutionResult
}
