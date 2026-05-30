package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.webdav.Dav4jvmWebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        private val clientFactory: Dav4jvmWebDavClientFactory,
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
            val endpointUrl = dataStore.webDavEndpointUrl.first()
            val username = dataStore.webDavUsername.first()
            if (!endpointUrl.isNullOrBlank() && !username.isNullOrBlank()) {
                clientFactory.invalidate(endpointUrl, username)
            }
        }

        override suspend fun getPasswordStatus(): StoredCredentialStatus = credentialStore.passwordStatus

        override suspend fun getCredentialState(): CredentialState =
            CredentialState(
                provider = CredentialProvider.WEBDAV,
                fields =
                    listOf(
                        CredentialFieldState(CredentialField.WEBDAV_USERNAME, effectiveUsernameStatus()),
                        CredentialFieldState(CredentialField.WEBDAV_PASSWORD, getPasswordStatus()),
                    ),
            )

        override suspend fun isPasswordConfigured(): Boolean = getPasswordStatus().isConfigured

        override suspend fun setAutoSyncEnabled(enabled: Boolean) {
            dataStore.updateWebDavAutoSyncEnabled(enabled)
        }

        override suspend fun setAutoSyncInterval(interval: String) {
            dataStore.updateWebDavAutoSyncInterval(interval)
        }

        override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
            dataStore.updateWebDavSyncOnRefresh(enabled)
        }

        private suspend fun effectiveUsernameStatus(): StoredCredentialStatus {
            val dataStoreUsername = dataStore.webDavUsername.first()
            return if (dataStoreUsername.isNullOrBlank()) {
                credentialStore.usernameStatus
            } else {
                StoredCredentialStatus.Present
            }
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
    get() = name.lowercase(java.util.Locale.ROOT)

internal fun webDavProviderFromPreference(value: String): WebDavProvider =
    WebDavProvider.entries.firstOrNull { it.preferenceValue == value.lowercase(java.util.Locale.ROOT) }
        ?: WebDavProvider.NUTSTORE
