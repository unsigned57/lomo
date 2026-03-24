package com.lomo.data.repository

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.git.GitSyncEngine
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncConfigurationRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : GitSyncConfigurationRepository {
        override fun isGitSyncEnabled(): Flow<Boolean> = dataStore.gitSyncEnabled

        override fun getRemoteUrl(): Flow<String?> = dataStore.gitRemoteUrl

        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.gitAutoSyncEnabled

        override fun getAutoSyncInterval(): Flow<String> = dataStore.gitAutoSyncInterval

        override fun observeLastSyncTimeMillis(): Flow<Long?> =
            dataStore.gitLastSyncTime.map { stored -> stored.takeIf { it > 0L } }

        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.gitSyncOnRefresh
    }

@Singleton
class GitSyncConfigurationMutationRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialStore: GitCredentialStore,
    ) : GitSyncConfigurationMutationRepository {
        override suspend fun setGitSyncEnabled(enabled: Boolean) {
            dataStore.updateGitSyncEnabled(enabled)
        }

        override suspend fun setRemoteUrl(url: String) {
            dataStore.updateGitRemoteUrl(url)
        }

        override suspend fun setToken(token: String) {
            credentialStore.setToken(token)
        }

        override suspend fun getToken(): String? = credentialStore.getToken()

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

@Singleton
class GitSyncStateRepositoryImpl
    @Inject
    constructor(
        private val gitSyncEngine: GitSyncEngine,
    ) : GitSyncStateRepository {
        override fun syncState(): Flow<SyncEngineState> = gitSyncEngine.syncState
    }
