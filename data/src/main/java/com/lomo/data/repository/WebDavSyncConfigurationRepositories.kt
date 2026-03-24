package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncConfigurationRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : WebDavSyncConfigurationRepository {
        override fun isWebDavSyncEnabled(): Flow<Boolean> = dataStore.webDavSyncEnabled

        override fun getProvider(): Flow<WebDavProvider> = dataStore.webDavProvider.map(::webDavProviderFromPreference)

        override fun getBaseUrl(): Flow<String?> = dataStore.webDavBaseUrl

        override fun getEndpointUrl(): Flow<String?> = dataStore.webDavEndpointUrl

        override fun getUsername(): Flow<String?> = dataStore.webDavUsername

        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.webDavAutoSyncEnabled

        override fun getAutoSyncInterval(): Flow<String> = dataStore.webDavAutoSyncInterval

        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.webDavSyncOnRefresh

        override fun observeLastSyncTimeMillis(): Flow<Long?> =
            dataStore.webDavLastSyncTime.map { stored -> stored.takeIf { it > 0L } }
    }

@Singleton
class WebDavSyncConfigurationMutationRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialStore: WebDavCredentialStore,
    ) : WebDavSyncConfigurationMutationRepository {
        override suspend fun setWebDavSyncEnabled(enabled: Boolean) {
            dataStore.updateWebDavSyncEnabled(enabled)
        }

        override suspend fun setProvider(provider: WebDavProvider) {
            dataStore.updateWebDavProvider(provider.preferenceValue)
        }

        override suspend fun setBaseUrl(url: String) {
            dataStore.updateWebDavBaseUrl(url.trim())
        }

        override suspend fun setEndpointUrl(url: String) {
            dataStore.updateWebDavEndpointUrl(url.trim())
        }

        override suspend fun setUsername(username: String) {
            val normalized = username.trim()
            dataStore.updateWebDavUsername(normalized)
            credentialStore.setUsername(normalized)
        }

        override suspend fun setPassword(password: String) {
            credentialStore.setPassword(password.trim())
        }

        override suspend fun isPasswordConfigured(): Boolean = !credentialStore.getPassword().isNullOrBlank()

        override suspend fun setAutoSyncEnabled(enabled: Boolean) {
            dataStore.updateWebDavAutoSyncEnabled(enabled)
        }

        override suspend fun setAutoSyncInterval(interval: String) {
            dataStore.updateWebDavAutoSyncInterval(interval)
        }

        override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
            dataStore.updateWebDavSyncOnRefresh(enabled)
        }
    }

@Singleton
class WebDavSyncStateRepositoryImpl
    @Inject
    constructor(
        private val stateHolder: WebDavSyncStateHolder,
    ) : WebDavSyncStateRepository {
        override fun syncState(): Flow<WebDavSyncState> = stateHolder.state
    }

internal val WebDavProvider.preferenceValue: String
    get() = name.lowercase()

internal fun webDavProviderFromPreference(value: String): WebDavProvider =
    WebDavProvider.entries.firstOrNull { it.preferenceValue == value.lowercase() } ?: WebDavProvider.NUTSTORE
