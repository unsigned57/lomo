package com.lomo.data.repository

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.sync.pendingreview.PendingReviewTable
import com.lomo.domain.repository.SyncStateResetRepository

class SyncStateResetRepositoryImpl(
    private val pendingReviewTable: PendingReviewTable,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val remoteSync: RemoteSyncRepository,
) : SyncStateResetRepository {
    override suspend fun resetWorkspaceScopedSyncState() {
        val root = workspaceRoot.absolutePathOrNull()
        if (!root.isNullOrBlank()) {
            remoteSync.resetControlTree(root)
        }
        pendingReviewTable.clearAll()
    }
}
