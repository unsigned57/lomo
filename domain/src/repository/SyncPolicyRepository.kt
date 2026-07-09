package com.lomo.domain.repository

import com.lomo.domain.model.SyncBackendType
import kotlinx.coroutines.flow.Flow

interface SyncPolicyRepository {
    fun ensureCoreSyncActive()

    fun observeRemoteSyncBackend(): Flow<SyncBackendType>

    suspend fun setRemoteSyncBackend(type: SyncBackendType)

    suspend fun applyRemoteSyncPolicy()
}
