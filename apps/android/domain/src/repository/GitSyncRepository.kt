package com.lomo.domain.repository

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.isConfigured
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

interface GitSyncConfigurationRepository {
    fun isGitSyncEnabled(): Flow<Boolean>

    fun getRemoteUrl(): Flow<String?>

    fun getBranch(): Flow<String>

    fun getAutoSyncEnabled(): Flow<Boolean>

    fun getAutoSyncInterval(): Flow<String>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeLastSyncInstant(): Flow<Instant?> =
        observeLastSyncTimeMillis().map { value ->
            value?.let(Instant::ofEpochMilli)
        }

    fun getSyncOnRefreshEnabled(): Flow<Boolean>
}

interface GitSyncConnectionMutationRepository {
    suspend fun setRemoteUrl(url: String)

    suspend fun setBranch(branch: String)
}

interface GitSyncCredentialMutationRepository {
    suspend fun setToken(token: String)

    suspend fun getTokenStatus(): StoredCredentialStatus

    suspend fun isTokenConfigured(): Boolean = getTokenStatus().isConfigured
}

interface GitSyncAuthorMutationRepository {
    suspend fun setAuthorInfo(
        name: String,
        email: String,
    )

    fun getAuthorName(): Flow<String>

    fun getAuthorEmail(): Flow<String>
}

interface GitSyncScheduleMutationRepository {
    suspend fun setAutoSyncEnabled(enabled: Boolean)

    suspend fun setAutoSyncInterval(interval: String)

    suspend fun setSyncOnRefreshEnabled(enabled: Boolean)
}

interface GitSyncConfigurationMutationRepository :
    GitSyncConnectionMutationRepository,
    GitSyncCredentialMutationRepository,
    GitSyncAuthorMutationRepository,
    GitSyncScheduleMutationRepository

interface GitSyncOperationRepository {
    suspend fun initOrClone(): GitSyncResult

    /**
     * Enqueues one Rust-owned sync cycle and returns [GitSyncResult.Accepted] on admission.
     *
     * The durable cycle record (`syncState()`/`getStatus()`) owns the terminal outcome —
     * the enqueue receipt never reports completion.
     */
    suspend fun sync(): GitSyncResult

    suspend fun getStatus(): GitSyncStatus

    suspend fun testConnection(): GitSyncResult

    /**
     * Clears the durable sync control tree; the next cycle is a first takeover.
     * Force-push/reset-to-remote are permanently retired — Sync Center owns recovery.
     */
    suspend fun resetRepository(): GitSyncResult
}

interface GitSyncStateRepository {
    fun syncState(): Flow<UnifiedSyncState>
}

interface GitSyncRepository :
    GitSyncConfigurationRepository,
    GitSyncConfigurationMutationRepository,
    GitSyncOperationRepository,
    GitSyncStateRepository
