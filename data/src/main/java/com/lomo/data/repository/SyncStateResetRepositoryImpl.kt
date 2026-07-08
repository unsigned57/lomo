package com.lomo.data.repository
import com.lomo.data.git.GitMediaSyncStateStore
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.domain.repository.SyncStateResetRepository
class SyncStateResetRepositoryImpl
constructor(
        private val syncStateResetDao: SyncStateResetDao,
        private val gitMediaSyncStateStore: GitMediaSyncStateStore,
    ) : SyncStateResetRepository {
        override suspend fun resetWorkspaceScopedSyncState() {
            syncStateResetDao.clearWorkspaceScopedSyncStateAllGenerations()
            gitMediaSyncStateStore.clear()
        }
    }
