package com.lomo.domain.repository

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.model.isConfigured
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

interface WebDavSyncConfigurationRepository {
    fun isWebDavSyncEnabled(): Flow<Boolean>

    fun getProvider(): Flow<WebDavProvider>

    fun getBaseUrl(): Flow<String?>

    fun getEndpointUrl(): Flow<String?>

    fun getUsername(): Flow<String?>

    fun getAutoSyncEnabled(): Flow<Boolean>

    fun getAutoSyncInterval(): Flow<String>

    fun getSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeLastSyncInstant(): Flow<Instant?> =
        observeLastSyncTimeMillis().map { value ->
            value?.let(Instant::ofEpochMilli)
        }
}

interface WebDavSyncConnectionMutationRepository {
    suspend fun setProvider(provider: WebDavProvider)

    suspend fun setBaseUrl(url: String)

    suspend fun setEndpointUrl(url: String)

    suspend fun setUsername(username: String)
}

interface WebDavSyncCredentialMutationRepository {
    suspend fun setPassword(password: String)

    suspend fun getPasswordStatus(): StoredCredentialStatus =
        if (isPasswordConfigured()) {
            StoredCredentialStatus.Present
        } else {
            StoredCredentialStatus.Missing
        }

    suspend fun getCredentialState(): CredentialState =
        CredentialState(
            provider = CredentialProvider.WEBDAV,
            fields =
                listOf(
                    CredentialFieldState(CredentialField.WEBDAV_USERNAME, StoredCredentialStatus.Missing),
                    CredentialFieldState(CredentialField.WEBDAV_PASSWORD, getPasswordStatus()),
                ),
        )

    suspend fun isPasswordConfigured(): Boolean
}

interface WebDavSyncScheduleMutationRepository {
    suspend fun setAutoSyncEnabled(enabled: Boolean)

    suspend fun setAutoSyncInterval(interval: String)

    suspend fun setSyncOnRefreshEnabled(enabled: Boolean)
}

interface WebDavSyncConfigurationMutationRepository :
    WebDavSyncConnectionMutationRepository,
    WebDavSyncCredentialMutationRepository,
    WebDavSyncScheduleMutationRepository

interface WebDavSyncOperationRepository {
    /**
     * Enqueues one Rust-owned sync cycle and returns [WebDavSyncResult.Accepted] on admission.
     * The durable cycle record owns the terminal outcome.
     */
    suspend fun sync(): WebDavSyncResult

    suspend fun getStatus(): WebDavSyncStatus

    suspend fun testConnection(): WebDavSyncResult
}

interface WebDavSyncStateRepository {
    fun syncState(): Flow<WebDavSyncState>
}

interface WebDavSyncRepository :
    WebDavSyncConfigurationRepository,
    WebDavSyncConfigurationMutationRepository,
    WebDavSyncOperationRepository,
    WebDavSyncStateRepository
