package com.lomo.app.testing.fakes

import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeWebDavSyncRepository : WebDavSyncRepository {
    private val _isWebDavSyncEnabled = MutableStateFlow(false)
    private val _syncOnRefreshEnabled = MutableStateFlow(false)

    fun updateWebDavSyncEnabled(enabled: Boolean) {
        _isWebDavSyncEnabled.value = enabled
    }

    fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        _syncOnRefreshEnabled.value = enabled
    }

    override fun isWebDavSyncEnabled(): Flow<Boolean> = _isWebDavSyncEnabled.asStateFlow()
    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = _syncOnRefreshEnabled.asStateFlow()

    override suspend fun setWebDavSyncEnabled(enabled: Boolean) { _isWebDavSyncEnabled.value = enabled }
    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) { _syncOnRefreshEnabled.value = enabled }

    override fun getProvider(): Flow<com.lomo.domain.model.WebDavProvider> = MutableStateFlow(com.lomo.domain.model.WebDavProvider.NUTSTORE)
    override suspend fun setProvider(provider: com.lomo.domain.model.WebDavProvider) {}
    override fun getBaseUrl(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setBaseUrl(url: String) {}
    override fun getEndpointUrl(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setEndpointUrl(url: String) {}
    override fun getUsername(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setUsername(username: String) {}
    override suspend fun setPassword(password: String) {}
    override suspend fun isPasswordConfigured(): Boolean = false
    override fun getAutoSyncEnabled(): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setAutoSyncEnabled(enabled: Boolean) {}
    override fun getAutoSyncInterval(): Flow<String> = MutableStateFlow("15m")
    override suspend fun setAutoSyncInterval(interval: String) {}
    override fun observeLastSyncTimeMillis(): Flow<Long?> = MutableStateFlow(null)

    override suspend fun sync(): com.lomo.domain.model.WebDavSyncResult = com.lomo.domain.model.WebDavSyncResult.Success("")
    override suspend fun getStatus(): com.lomo.domain.model.WebDavSyncStatus = com.lomo.domain.model.WebDavSyncStatus(0, 0, 0, null)
    override suspend fun testConnection(): com.lomo.domain.model.WebDavSyncResult = com.lomo.domain.model.WebDavSyncResult.Success("")

    override suspend fun resolveConflicts(
        resolution: com.lomo.domain.model.SyncConflictResolution,
        conflictSet: com.lomo.domain.model.SyncConflictSet,
    ): com.lomo.domain.model.WebDavSyncResult = com.lomo.domain.model.WebDavSyncResult.Success("")

    override suspend fun resolveReview(
        resolution: com.lomo.domain.model.SyncReviewResolution,
        review: com.lomo.domain.model.SyncReviewSession,
    ): com.lomo.domain.model.WebDavSyncResult = com.lomo.domain.model.WebDavSyncResult.Success("")

    override fun syncState(): Flow<com.lomo.domain.model.WebDavSyncState> = MutableStateFlow(com.lomo.domain.model.WebDavSyncState.Idle)
}
