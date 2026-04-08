package com.lomo.app.feature.conflict

import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import kotlinx.collections.immutable.ImmutableMap

sealed interface SyncConflictDialogState {
    data object Hidden : SyncConflictDialogState

    data class Showing(
        val conflictSet: SyncConflictSet,
        val perFileChoices: ImmutableMap<String, SyncConflictResolutionChoice>,
        val expandedFilePath: String?,
        val isResolving: Boolean,
    ) : SyncConflictDialogState
}
