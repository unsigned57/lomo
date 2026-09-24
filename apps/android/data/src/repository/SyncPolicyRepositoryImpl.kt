package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.GitEndpointSecurityMigration
import com.lomo.data.worker.CoreSyncScheduler
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.SyncStateResetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber

internal class SyncPolicyRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val coreSyncScheduler: CoreSyncScheduler,
    private val rustSyncScheduler: RustSyncScheduler,
    private val syncStateReset: SyncStateResetRepository,
    private val gitEndpointSecurityMigration: GitEndpointSecurityMigration,
) : SyncPolicyRepository {
    override fun ensureCoreSyncActive() {
        coreSyncScheduler.ensureActive()
    }

    override fun observeRemoteSyncBackend(): Flow<SyncBackendType> =
        dataStore.syncBackendType.map(SyncBackendType::fromStorageValue)

    override suspend fun setRemoteSyncBackend(type: SyncBackendType) {
        require(type != SyncBackendType.UNKNOWN) {
            "Cannot persist an unrecognized backend; UNKNOWN is a read-side unavailable state"
        }
        val previous = SyncBackendType.fromStorageValue(dataStore.syncBackendType.first())
        if (previous != type) {
            rustSyncScheduler.cancel()
            syncStateReset.resetWorkspaceScopedSyncState()
        }
        dataStore.setRemoteSyncBackendType(type.storageValue())
    }

    override suspend fun applyRemoteSyncPolicy() {
        // Legacy userinfo endpoints must be sanitized before any schedule decision reads them;
        // the migration is content-detected and a no-op on clean config.
        gitEndpointSecurityMigration.migrateIfNeeded()
        when (SyncBackendType.fromStorageValue(dataStore.syncBackendType.first())) {
            SyncBackendType.NONE,
            SyncBackendType.INBOX,
            -> rustSyncScheduler.cancel()
            SyncBackendType.UNKNOWN -> {
                // The stored selection is unparseable — we cannot tell which backend the user
                // meant, so we neither schedule nor destroy the existing schedule.
                Timber.w("applyRemoteSyncPolicy: unrecognized stored backend; leaving schedule untouched")
            }
            SyncBackendType.GIT,
            SyncBackendType.WEBDAV,
            SyncBackendType.S3,
            -> rustSyncScheduler.reschedule()
        }
    }
}
