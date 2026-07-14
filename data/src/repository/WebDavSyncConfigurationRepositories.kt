package com.lomo.data.repository
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.webdav.OkHttpWebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
class WebDavSyncConfigurationRepositoryImpl
constructor(
        private val dataStore: LomoDataStore,
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
    ) : WebDavSyncConfigurationRepository {
        override fun isWebDavSyncEnabled(): Flow<Boolean> = dataStore.webDavSyncEnabled
        override fun getProvider(): Flow<WebDavProvider> = dataStore.webDavProvider.map(::webDavProviderFromPreference)
        override fun getBaseUrl(): Flow<String?> = dataStore.webDavBaseUrl
        override fun getEndpointUrl(): Flow<String?> = dataStore.webDavEndpointUrl
        override fun getUsername(): Flow<String?> =
            credentialRepository
                .observeCredentialState(CredentialProvider.WEBDAV)
                .transform {
                    emit(credentialRepository.readWebDavUsernameForDisplay(securitySessionPolicy))
                }
        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.webDavAutoSyncEnabled
        override fun getAutoSyncInterval(): Flow<String> = dataStore.webDavAutoSyncInterval
        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.webDavSyncOnRefresh
        override fun observeLastSyncTimeMillis(): Flow<Long?> =
            dataStore.webDavLastSyncTime.map { stored -> stored.takeIf { it > 0L } }
    }
class WebDavSyncConfigurationMutationRepositoryImpl
constructor(
        private val dataStore: LomoDataStore,
        private val credentialStore: WebDavCredentialStore,
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
        private val clientFactory: OkHttpWebDavClientFactory,
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
            credentialRepository.writeSecret(CredentialField.WEBDAV_USERNAME, normalized)
        }
        override suspend fun setPassword(password: String) {
            credentialRepository.writeSecret(CredentialField.WEBDAV_PASSWORD, password.trim())
            val endpointUrl = dataStore.webDavEndpointUrl.first()
            val username = credentialRepository.readWebDavUsernameForDisplay(securitySessionPolicy)
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
            dataStore.webDavUsername.first()?.takeIf(String::isNotBlank)?.let { legacyUsername ->
                credentialRepository.writeSecret(CredentialField.WEBDAV_USERNAME, legacyUsername)
                dataStore.updateWebDavUsername(null)
            }
            return credentialStore.usernameStatus
        }
    }
class WebDavSyncStateRepositoryImpl
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
private suspend fun CredentialRepository.readWebDavUsernameForDisplay(
    securitySessionPolicy: SecuritySessionPolicy,
): String? =
    when (
        val result =
            readSecret(
                field = CredentialField.WEBDAV_USERNAME,
                authorization = securitySessionPolicy.authorizeCredentialRead(),
            )
    ) {
        CredentialSecretReadResult.Missing -> null
        is CredentialSecretReadResult.Present -> result.value
        CredentialSecretReadResult.Unreadable -> null
        is CredentialSecretReadResult.Unauthorized -> null
    }
