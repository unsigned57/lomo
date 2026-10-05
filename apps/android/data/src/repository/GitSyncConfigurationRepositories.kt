package com.lomo.data.repository

import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.toUnifiedSyncState
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.data.worker.GIT_AUTHOR_EMAIL_DEFAULT
import com.lomo.data.worker.GIT_AUTHOR_NAME_DEFAULT
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
import kotlinx.coroutines.flow.first
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

/**
 * Git config writes. Remote URL, branch and commit-author fields are canonical remote
 * identity (`SyncBackendConfig::canonical_identity`): changing any of them invalidates the
 * durable `.lomo/sync/v1` tree minted under the old identity, so [SyncIdentityResetPolicy]
 * disposes it before the write lands. Re-writing the stored value and non-identity settings
 * (token, autosync) leave durable state untouched.
 */
class GitSyncConfigurationMutationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val credentialRepository: CredentialRepository,
    private val identityReset: SyncIdentityResetPolicy,
) : GitSyncConfigurationMutationRepository {
    override suspend fun setRemoteUrl(url: String) {
        // Compare through the same trim the cycle input factory applies — a cosmetic
        // whitespace edit is not an identity change.
        val normalized = url.trim()
        if (dataStore.gitRemoteUrl.first()?.trim().orEmpty() != normalized) {
            identityReset.resetIdentityScopedSyncState()
        }
        dataStore.updateGitRemoteUrl(normalized)
    }

    override suspend fun setBranch(branch: String) {
        val normalized = branch.trim()
        if (dataStore.gitBranch.first().trim() != normalized) {
            identityReset.resetIdentityScopedSyncState()
        }
        dataStore.updateGitBranch(normalized)
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
        // Canonical identity carries the *effective* author: the input factory substitutes
        // defaults for blanks, so compare effective values on both sides.
        if (dataStore.gitAuthorName.first().trim().ifBlank { GIT_AUTHOR_NAME_DEFAULT } !=
            name.trim().ifBlank { GIT_AUTHOR_NAME_DEFAULT } ||
            dataStore.gitAuthorEmail.first().trim().ifBlank { GIT_AUTHOR_EMAIL_DEFAULT } !=
            email.trim().ifBlank { GIT_AUTHOR_EMAIL_DEFAULT }
        ) {
            identityReset.resetIdentityScopedSyncState()
        }
        dataStore.updateGitAuthorName(name.trim())
        dataStore.updateGitAuthorEmail(email.trim())
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
