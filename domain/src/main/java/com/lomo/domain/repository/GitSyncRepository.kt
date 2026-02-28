package com.lomo.domain.repository

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.MemoVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

interface GitSyncRepository {
    // Configuration
    fun isGitSyncEnabled(): Flow<Boolean>
    fun getRemoteUrl(): Flow<String?>
    fun getAutoSyncEnabled(): Flow<Boolean>
    fun getAutoSyncInterval(): Flow<String>
    @Deprecated(
        message = "Uses 0 as sentinel for unknown sync time. Prefer observeLastSyncTimeMillis().",
        replaceWith = ReplaceWith("observeLastSyncTimeMillis()"),
    )
    fun getLastSyncTime(): Flow<Long>
    fun observeLastSyncTimeMillis(): Flow<Long?> =
        getLastSyncTime().map { stored -> stored.takeIf { it > 0L } }

    fun observeLastSyncInstant(): Flow<Instant?> =
        observeLastSyncTimeMillis().map { value -> value?.let(Instant::ofEpochMilli) }

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
    /**
     * Runs a full sync cycle and reports terminal outcomes through [GitSyncResult].
     * Implementations should return [GitSyncResult.Error] for partial failures too,
     * such as a git success followed by an index refresh failure.
     */
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
