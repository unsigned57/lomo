package com.lomo.app.feature.conflict

import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

sealed interface SyncConflictDialogState {
    data object Hidden : SyncConflictDialogState

    data class Showing(
        val conflictSet: SyncConflictSet,
        val perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
        val expandedFilePath: String?,
        val isResolving: Boolean,
    ) : SyncConflictDialogState

    data class ReviewShowing(
        val reviewSession: SyncReviewSession,
        val perItemChoices: ImmutableMap<String, SyncReviewResolutionChoice>,
        val blockedPaths: ImmutableSet<String> = persistentSetOf(),
        val isInitialImportPreview: Boolean =
            reviewSession.kind == SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
        val expandedFilePath: String?,
        val isResolving: Boolean,
    ) : SyncConflictDialogState
}
