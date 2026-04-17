package com.lomo.domain.repository

import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.flow.Flow

interface SyncInboxRepository {
    fun syncState(): Flow<UnifiedSyncState>

    suspend fun ensureDirectoryStructure()

    suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult

    suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): UnifiedSyncResult
}
