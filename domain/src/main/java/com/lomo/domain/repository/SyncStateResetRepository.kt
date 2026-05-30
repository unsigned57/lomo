package com.lomo.domain.repository

/**
 * Clears sync state that is scoped to the currently selected workspace root.
 *
 * Domain owns the lifecycle trigger; data owns the concrete WebDAV, S3, and conflict stores.
 */
interface SyncStateResetRepository {
    suspend fun resetWorkspaceScopedSyncState()
}
