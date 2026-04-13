package com.lomo.domain.repository

import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet

interface SyncInboxRepository {
    suspend fun processPendingInbox()

    suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): SyncInboxConflictResolutionResult
}

sealed interface SyncInboxConflictResolutionResult {
    data object Resolved : SyncInboxConflictResolutionResult

    data class Pending(
        val conflictSet: SyncConflictSet,
    ) : SyncInboxConflictResolutionResult
}
