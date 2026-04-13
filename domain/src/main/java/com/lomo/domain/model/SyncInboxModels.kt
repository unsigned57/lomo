package com.lomo.domain.model

sealed interface SyncInboxConflictResolutionResult {
    data object Resolved : SyncInboxConflictResolutionResult

    data class Pending(
        val conflictSet: SyncConflictSet,
    ) : SyncInboxConflictResolutionResult
}
