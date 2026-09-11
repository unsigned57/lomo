package com.lomo.data.repository

import com.lomo.data.sync.pendingreview.PendingReviewTable
import com.lomo.domain.repository.SyncStateResetRepository

class SyncStateResetRepositoryImpl(
    private val pendingReviewTable: PendingReviewTable,
) : SyncStateResetRepository {
    override suspend fun resetWorkspaceScopedSyncState() {
        pendingReviewTable.clearAll()
    }
}
