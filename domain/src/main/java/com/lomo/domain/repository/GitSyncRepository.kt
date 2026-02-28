package com.lomo.domain.repository

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.MemoVersion
import kotlinx.coroutines.flow.Flow

interface GitSyncRepository {
    // Configuration
    fun isGitSyncEnabled(): Flow<Boolean>
    fun getRemoteUrl(): Flow<String?>
    fun getAutoSyncEnabled(): Flow<Boolean>
    fun getAutoSyncInterval(): Flow<String>
    fun getLastSyncTime(): Flow<Long>
    fun getSyncOnRefreshEnabled(): Flow<Boolean>

    suspend fun setGitSyncEnabled(enabled: Boolean)
    suspend fun setRemoteUrl(url: String)
    suspend fun setToken(token: String)
    suspend fun getToken(): String?
    suspend fun setAuthorInfo(name: String, email: String)
    fun getAuthorName(): Flow<String>
    fun getAuthorEmail(): Flow<String>
    suspend fun setAutoSyncEnabled(enabled: Boolean)
    suspend fun setAutoSyncInterval(interval: String)
    suspend fun setSyncOnRefreshEnabled(enabled: Boolean)

    // Operations
    suspend fun initOrClone(): GitSyncResult
    suspend fun sync(): GitSyncResult
    suspend fun getStatus(): GitSyncStatus
    suspend fun testConnection(): GitSyncResult
    suspend fun resetRepository(): GitSyncResult
    suspend fun resetLocalBranchToRemote(): GitSyncResult
    suspend fun forcePushLocalToRemote(): GitSyncResult

    // Version History
    suspend fun getMemoVersionHistory(dateKey: String, memoTimestamp: Long): List<MemoVersion>

    // State observation
    fun syncState(): Flow<SyncEngineState>
}
