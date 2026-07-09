package com.lomo.app.feature.conflict

import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewAutoResolutionAdvisor
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.usecase.SyncConflictResolutionResult
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.SyncReviewResolutionResult
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

internal fun buildSuggestedChoices(
    conflictSet: SyncConflictSet,
    blockedPaths: Set<String> = emptySet(),
): ImmutableMap<String, SyncConflictResolutionChoice> =
    conflictSet.files.mapNotNull { file ->
        if (file.relativePath in blockedPaths) return@mapNotNull null
        SyncConflictAutoResolutionAdvisor.suggestedChoice(file)?.let { choice ->
            file.relativePath to choice
        }
    }.toMap().toImmutableMap()

internal fun buildSafeChoices(conflictSet: SyncConflictSet): ImmutableMap<String, SyncConflictResolutionChoice> =
    conflictSet.files.mapNotNull { file ->
        SyncConflictAutoResolutionAdvisor.safeAutoResolutionChoice(file)?.let { choice ->
            file.relativePath to choice
        }
    }.toMap().toImmutableMap()

internal fun buildReviewSuggestedChoices(
    review: SyncReviewSession,
    blockedPaths: Set<String>,
): ImmutableMap<String, SyncReviewResolutionChoice> =
    review.items.mapNotNull { item ->
        if (item.relativePath in blockedPaths) return@mapNotNull null
        SyncReviewAutoResolutionAdvisor.suggestedChoice(item)?.let { choice ->
            item.relativePath to choice
        }
    }.toMap().toImmutableMap()

internal fun buildReviewSafeChoices(
    review: SyncReviewSession,
    blockedPaths: Set<String>,
): ImmutableMap<String, SyncReviewResolutionChoice> =
    review.items.mapNotNull { item ->
        if (item.relativePath in blockedPaths) return@mapNotNull null
        SyncReviewAutoResolutionAdvisor.safeAutoResolutionChoice(item)?.let { choice ->
            item.relativePath to choice
        }
    }.toMap().toImmutableMap()

internal suspend fun resolveConflictDialogState(
    current: SyncConflictDialogState.Showing,
    useCase: SyncConflictResolutionUseCase,
): SyncConflictDialogState =
    when (
        val result =
            useCase.resolve(
                conflictSet = current.conflictSet,
                resolution = SyncConflictResolution(current.perFileChoices),
            )
    ) {
        SyncConflictResolutionResult.Resolved -> SyncConflictDialogState.Hidden
        is SyncConflictResolutionResult.Pending -> pendingConflictState(current, result.conflictSet)
    }

internal suspend fun resolveReviewDialogState(
    current: SyncConflictDialogState.ReviewShowing,
    useCase: SyncReviewResolutionUseCase,
): SyncConflictDialogState =
    when (
        val result =
            useCase.resolve(
                review = current.reviewSession,
                resolution = SyncReviewResolution(current.perItemChoices),
            )
    ) {
        SyncReviewResolutionResult.Resolved -> SyncConflictDialogState.Hidden
        is SyncReviewResolutionResult.Pending -> pendingReviewState(current, result.review)
    }

internal fun SyncReviewSession.blockedPaths(): ImmutableSet<String> =
    items
        .filter { item -> item.state == SyncReviewItemState.BLOCKED }
        .map { item -> item.relativePath }
        .toImmutableSet()

internal fun SyncConflictDialogState.isResolving(): Boolean =
    when (this) {
        SyncConflictDialogState.Hidden -> false
        is SyncConflictDialogState.Showing -> isResolving
        is SyncConflictDialogState.ReviewShowing -> isResolving
    }

internal fun SyncConflictDialogState.withResolving(isResolving: Boolean): SyncConflictDialogState =
    when (this) {
        SyncConflictDialogState.Hidden -> this
        is SyncConflictDialogState.Showing -> copy(isResolving = isResolving)
        is SyncConflictDialogState.ReviewShowing -> copy(isResolving = isResolving)
    }

private fun pendingConflictState(
    current: SyncConflictDialogState.Showing,
    conflictSet: SyncConflictSet,
): SyncConflictDialogState.Showing {
    val remainingChoices =
        current.perFileChoices
            .filterKeys { path -> conflictSet.files.any { file -> file.relativePath == path } }
            .toImmutableMap()
    return SyncConflictDialogState.Showing(
        conflictSet = conflictSet,
        perFileChoices = (buildSuggestedChoices(conflictSet) + remainingChoices).toImmutableMap(),
        expandedFilePath = null,
        isResolving = false,
    )
}

private fun pendingReviewState(
    current: SyncConflictDialogState.ReviewShowing,
    review: SyncReviewSession,
): SyncConflictDialogState.ReviewShowing {
    val remainingChoices =
        current.perItemChoices
            .filterKeys { path -> review.items.any { item -> item.relativePath == path } }
            .toImmutableMap()
    val blockedPaths = review.blockedPaths()
    return SyncConflictDialogState.ReviewShowing(
        reviewSession = review,
        perItemChoices =
            (buildReviewSuggestedChoices(review, blockedPaths) + remainingChoices)
                .filterKeys { path -> path !in blockedPaths }
                .toImmutableMap(),
        blockedPaths = blockedPaths,
        expandedFilePath = null,
        isResolving = false,
    )
}
