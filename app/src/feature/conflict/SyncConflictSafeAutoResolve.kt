package com.lomo.app.feature.conflict

import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.supportsDeferredConflictResolution
import com.lomo.domain.model.supportsDeferredReviewResolution
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

internal fun SyncConflictDialogState.Showing.safeAutoResolveChoices():
    ImmutableMap<String, SyncConflictResolutionChoice>? {
    if (isResolving) return null
    val safeChoices = buildSafeChoices(conflictSet)
    if (safeChoices.isEmpty()) return null
    val supportsSkip = conflictSet.source.supportsDeferredConflictResolution()
    if (!supportsSkip && safeChoices.size != conflictSet.files.size) return null
    return conflictSet.files.associate { file ->
        file.relativePath to (safeChoices[file.relativePath] ?: SyncConflictResolutionChoice.SKIP_FOR_NOW)
    }.toImmutableMap()
}

internal fun SyncConflictDialogState.ReviewShowing.safeAutoResolveChoices():
    ImmutableMap<String, SyncReviewResolutionChoice>? {
    if (isResolving) return null
    val safeChoices = buildReviewSafeChoices(reviewSession, blockedPaths)
    if (safeChoices.isEmpty()) return null
    val supportsSkip = reviewSession.source.supportsDeferredReviewResolution()
    val selectableItems = reviewSession.items.filterNot { item -> item.relativePath in blockedPaths }
    if (!supportsSkip && safeChoices.size != selectableItems.size) return null
    return selectableItems.associate { item ->
        item.relativePath to (safeChoices[item.relativePath] ?: SyncReviewResolutionChoice.SKIP_FOR_NOW)
    }.toImmutableMap()
}
