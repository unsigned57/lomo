package com.lomo.data.repository

import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.domain.repository.SyncStateResetRepository

class SyncStateResetRepositoryImpl(
    private val syncStateResetDao: SyncStateResetDao,
) : SyncStateResetRepository {
    override suspend fun resetWorkspaceScopedSyncState() {
        syncStateResetDao.clearWorkspaceScopedSyncStateAllGenerations()
    }
}
