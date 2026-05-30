package com.lomo.app.testing.fakes

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.GitSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeGitSyncRepository : GitSyncRepository {
    private val _isGitSyncEnabled = MutableStateFlow(false)
    private val _syncOnRefreshEnabled = MutableStateFlow(false)

    fun updateGitSyncEnabled(enabled: Boolean) {
        _isGitSyncEnabled.value = enabled
    }

    fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        _syncOnRefreshEnabled.value = enabled
    }

    override fun isGitSyncEnabled(): Flow<Boolean> = _isGitSyncEnabled.asStateFlow()
    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = _syncOnRefreshEnabled.asStateFlow()

    override suspend fun setGitSyncEnabled(enabled: Boolean) { _isGitSyncEnabled.value = enabled }
    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) { _syncOnRefreshEnabled.value = enabled }

    override fun getRemoteUrl(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setRemoteUrl(url: String) {}
    
    override suspend fun setToken(token: String) {}
    override suspend fun getToken(): String? = null
    override suspend fun setAuthorInfo(name: String, email: String) {}
    override fun getAuthorName(): Flow<String> = MutableStateFlow("")
    override fun getAuthorEmail(): Flow<String> = MutableStateFlow("")

    override fun getAutoSyncEnabled(): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setAutoSyncEnabled(enabled: Boolean) {}
    override fun getAutoSyncInterval(): Flow<String> = MutableStateFlow("15m")
    override suspend fun setAutoSyncInterval(interval: String) {}
    override fun observeLastSyncTimeMillis(): Flow<Long?> = MutableStateFlow(null)

    var syncResult: GitSyncResult = GitSyncResult.Success("")
    var testConnectionResult: GitSyncResult = GitSyncResult.Success("")
    var initOrCloneResult: GitSyncResult = GitSyncResult.Success("")

    override suspend fun initOrClone(): GitSyncResult = initOrCloneResult
    override suspend fun sync(): GitSyncResult = syncResult
    override suspend fun getStatus(): GitSyncStatus = GitSyncStatus(false, 0, 0, null)
    override suspend fun testConnection(): GitSyncResult = testConnectionResult
    override suspend fun resetRepository(): GitSyncResult = GitSyncResult.Success("")
    override suspend fun resetLocalBranchToRemote(): GitSyncResult = GitSyncResult.Success("")
    override suspend fun forcePushLocalToRemote(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): GitSyncResult = GitSyncResult.Success("")

    override fun syncState(): Flow<UnifiedSyncState> = MutableStateFlow(UnifiedSyncState.Idle)
}
