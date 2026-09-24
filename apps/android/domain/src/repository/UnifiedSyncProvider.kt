package com.lomo.domain.repository

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.flow.Flow

interface UnifiedSyncProvider {
    val backendType: SyncBackendType

    fun isEnabled(): Flow<Boolean>

    fun isSyncOnRefreshEnabled(): Flow<Boolean>

    fun syncState(): Flow<UnifiedSyncState>

    suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult

    /**
     * Review-session resolution (Sync Inbox only — remote conflicts resolve exclusively through
     * the Rust expected-revision port; remote providers never produce review sessions).
     */
    suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult
}
