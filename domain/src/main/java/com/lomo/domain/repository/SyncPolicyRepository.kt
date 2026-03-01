package com.lomo.domain.repository

interface SyncPolicyRepository {
    fun ensureCoreSyncActive()

    suspend fun applyGitSyncPolicy()
}
