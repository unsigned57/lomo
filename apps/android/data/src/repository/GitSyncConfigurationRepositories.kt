package com.lomo.data.repository

import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.toUnifiedSyncState
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GitSyncConfigurationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val cycleStatus: RustSyncCycleStatusStore,
) : GitSyncConfigurationRepository {
    override fun isGitSyncEnabled(): Flow<Boolean> = dataStore.gitSyncEnabled

    override fun getRemoteUrl(): Flow<String?> = dataStore.gitRemoteUrl

    override fun getBranch(): Flow<String> = dataStore.gitBranch

    override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.gitAutoSyncEnabled

    override fun getAutoSyncInterval(): Flow<String> = dataStore.gitAutoSyncInterval

    /**
     * Last-successful sync timestamp from the durable cycle record (`cycle_state.rec`).
     *
     * The `gitLastSyncTime` DataStore write path is retired: the Rust-owned record owns
     * `last_successful_at_ms`; `null` means no successful apply cycle on record.
     */
    override fun observeLastSyncTimeMillis(): Flow<Long?> =
        cycleStatus.observe().map { status -> status?.lastSuccessfulAtMs }

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.gitSyncOnRefresh
}

class GitSyncConfigurationMutationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val credentialRepository: CredentialRepository,
) : GitSyncConfigurationMutationRepository {
    override suspend fun setRemoteUrl(url: String) {
        dataStore.updateGitRemoteUrl(url)
    }

    override suspend fun setBranch(branch: String) {
        dataStore.updateGitBranch(branch)
    }

    override suspend fun setToken(token: String) {
        credentialRepository.writeSecret(CredentialField.GIT_TOKEN, token)
    }

    override suspend fun getTokenStatus(): StoredCredentialStatus =
        credentialRepository
            .credentialState(CredentialProvider.GIT)
            .statusFor(CredentialField.GIT_TOKEN)

    override suspend fun isTokenConfigured(): Boolean = getTokenStatus().isConfigured

    override suspend fun setAuthorInfo(
        name: String,
        email: String,
    ) {
        dataStore.updateGitAuthorName(name)
        dataStore.updateGitAuthorEmail(email)
    }

    override fun getAuthorName(): Flow<String> = dataStore.gitAuthorName

    override fun getAuthorEmail(): Flow<String> = dataStore.gitAuthorEmail

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.updateGitAutoSyncEnabled(enabled)
    }

    override suspend fun setAutoSyncInterval(interval: String) {
        dataStore.updateGitAutoSyncInterval(interval)
    }

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
        dataStore.updateGitSyncOnRefresh(enabled)
    }
}

/**
 * Durable sync state for Git — `syncState()` emits real cycle-record reads, never an
 * in-memory `Idle` seed. `null` records map to [UnifiedSyncState.Idle] (never synced / no
 * Direct workspace root).
 */
class GitSyncStateRepositoryImpl(
    private val cycleStatus: RustSyncCycleStatusStore,
) : GitSyncStateRepository {
    override fun syncState(): Flow<UnifiedSyncState> =
        cycleStatus.observe().map { status -> status.toUnifiedSyncState(SyncBackendType.GIT) }
}
