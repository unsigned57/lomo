package com.lomo.data.local.dao

/**
 * Post P5-13: only independent Sync Inbox pending-review tables remain file-backed.
 * Remote-sync durable state lives under `.lomo/sync/v1` (Rust).
 */
interface SyncStateResetDao {
    suspend fun clearPendingSyncReviews()

    suspend fun clearWorkspaceScopedSyncStateAllGenerations() {
        clearPendingSyncReviews()
    }
}
