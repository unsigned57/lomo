package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.flow.Flow

internal interface RemoteSyncSharedStateObservation<TSyncState> {
    fun observeSyncEnabled(): Flow<Boolean>

    fun observeAutoSyncEnabled(): Flow<Boolean>

    fun observeAutoSyncInterval(): Flow<String>

    fun observeSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeSyncState(): Flow<TSyncState>
}

internal class RemoteSyncSharedStateObservationImpl<TSyncState>(
    private val enabled: () -> Flow<Boolean>,
    private val autoSyncEnabled: () -> Flow<Boolean>,
    private val autoSyncInterval: () -> Flow<String>,
    private val syncOnRefreshEnabled: () -> Flow<Boolean>,
    private val lastSyncTimeMillis: () -> Flow<Long?>,
    private val syncState: () -> Flow<TSyncState>,
) : RemoteSyncSharedStateObservation<TSyncState> {
    override fun observeSyncEnabled(): Flow<Boolean> = enabled()

    override fun observeAutoSyncEnabled(): Flow<Boolean> = autoSyncEnabled()

    override fun observeAutoSyncInterval(): Flow<String> = autoSyncInterval()

    override fun observeSyncOnRefreshEnabled(): Flow<Boolean> = syncOnRefreshEnabled()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = lastSyncTimeMillis()

    override fun observeSyncState(): Flow<TSyncState> = syncState()
}

internal interface RemoteSyncSharedMutation {
    suspend fun updateSyncEnabled(enabled: Boolean)

    suspend fun updateAutoSyncEnabled(enabled: Boolean)

    suspend fun updateAutoSyncInterval(interval: String)

    suspend fun updateSyncOnRefreshEnabled(enabled: Boolean)
}

internal class RemoteSyncSharedMutationImpl(
    private val backendType: SyncBackendType,
    private val syncPolicyRepository: SyncPolicyRepository,
    private val autoSyncEnabledUpdater: suspend (Boolean) -> Unit,
    private val autoSyncIntervalUpdater: suspend (String) -> Unit,
    private val syncOnRefreshUpdater: suspend (Boolean) -> Unit,
) : RemoteSyncSharedMutation {
    override suspend fun updateSyncEnabled(enabled: Boolean) {
        syncPolicyRepository.setRemoteSyncBackend(if (enabled) backendType else SyncBackendType.NONE)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateAutoSyncEnabled(enabled: Boolean) {
        autoSyncEnabledUpdater(enabled)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateAutoSyncInterval(interval: String) {
        autoSyncIntervalUpdater(interval)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        syncOnRefreshUpdater(enabled)
    }
}

internal interface RemoteSyncSharedActions<TResult> {
    suspend fun triggerSyncNow()

    suspend fun testConnection(): TResult
}

internal class RemoteSyncSharedActionsImpl<TResult>(
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
    private val connectionTester: suspend () -> TResult,
) : RemoteSyncSharedActions<TResult> {
    override suspend fun triggerSyncNow() {
        syncAndRebuildUseCase(forceSync = true)
    }

    override suspend fun testConnection(): TResult = connectionTester()
}
