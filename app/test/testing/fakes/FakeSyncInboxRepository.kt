package com.lomo.app.testing.fakes

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.SyncInboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSyncInboxRepository : SyncInboxRepository {
    var syncResult: UnifiedSyncResult = UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
    var syncCallCount = 0
        private set
    var lastOperation: UnifiedSyncOperation? = null
        private set

    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
        syncCallCount++
        lastOperation = operation
        return syncResult
    }

    override fun syncState(): Flow<com.lomo.domain.model.UnifiedSyncState> = MutableStateFlow(com.lomo.domain.model.UnifiedSyncState.Idle)
    override suspend fun ensureDirectoryStructure() {}
    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): UnifiedSyncResult = syncResult
}
