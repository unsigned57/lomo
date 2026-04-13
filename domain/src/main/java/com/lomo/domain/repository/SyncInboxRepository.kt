package com.lomo.domain.repository

import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncInboxConflictResolutionResult

interface SyncInboxRepository {
    suspend fun processPendingInbox()

    suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): SyncInboxConflictResolutionResult
}
