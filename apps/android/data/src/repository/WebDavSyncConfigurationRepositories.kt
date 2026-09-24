package com.lomo.data.repository

import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.toWebDavSyncState
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

class WebDavSyncConfigurationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val credentialRepository: CredentialRepository,
    private val securitySessionPolicy: SecuritySessionPolicy,
    private val cycleStatus: RustSyncCycleStatusStore,
) : WebDavSyncConfigurationRepository {
    override fun isWebDavSyncEnabled(): Flow<Boolean> = dataStore.webDavSyncEnabled

    override fun getProvider(): Flow<WebDavProvider> =
        dataStore.webDavProvider.map(::webDavProviderFromPreference)

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

    /**
     * Last-successful sync timestamp from the durable cycle record (`cycle_state.rec`).
     * The `webDavLastSyncTime` DataStore write path is retired.
     */
    override fun observeLastSyncTimeMillis(): Flow<Long?> =
        cycleStatus.observe().map { status -> status?.lastSuccessfulAtMs }
}

class WebDavSyncConfigurationMutationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val credentialStore: WebDavCredentialStore,
    private val credentialRepository: CredentialRepository,
) : WebDavSyncConfigurationMutationRepository {
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
        credentialRepository.writeSecret(CredentialField.WEBDAV_USERNAME, username)
    }

    override suspend fun setPassword(password: String) {
        credentialRepository.writeSecret(CredentialField.WEBDAV_PASSWORD, password)
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

/** Durable sync state for WebDAV — real cycle-record reads, never an in-memory `Idle` seed. */
class WebDavSyncStateRepositoryImpl(
    private val cycleStatus: RustSyncCycleStatusStore,
) : WebDavSyncStateRepository {
    override fun syncState(): Flow<WebDavSyncState> =
        cycleStatus.observe().map { status -> status.toWebDavSyncState() }
}

internal val WebDavProvider.preferenceValue: String
    get() = name.lowercase(java.util.Locale.ROOT)

internal fun webDavProviderFromPreference(value: String): WebDavProvider =
    WebDavProvider.entries.firstOrNull { it.preferenceValue == value.lowercase(java.util.Locale.ROOT) }
        ?: WebDavProvider.NUTSTORE

internal suspend fun CredentialRepository.readWebDavUsernameForDisplay(
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
