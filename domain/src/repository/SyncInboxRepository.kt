package com.lomo.domain.repository

import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.flow.Flow

interface SyncInboxRepository {
    fun syncState(): Flow<UnifiedSyncState>

    suspend fun ensureDirectoryStructure()

    suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult

    suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult
}
