package com.lomo.domain.usecase

import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.flow.Flow

interface WebDavSyncSettingsStateObservation {
    fun observeWebDavSyncEnabled(): Flow<Boolean>

    fun observeProvider(): Flow<WebDavProvider>

    fun observeBaseUrl(): Flow<String?>

    fun observeEndpointUrl(): Flow<String?>

    fun observeUsername(): Flow<String?>

    fun observeAutoSyncEnabled(): Flow<Boolean>

    fun observeAutoSyncInterval(): Flow<String>

    fun observeSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeSyncState(): Flow<WebDavSyncState>
}

interface WebDavSyncCredentialObservation {
    suspend fun getPasswordStatus(): StoredCredentialStatus

    suspend fun getCredentialState(): CredentialState

    suspend fun isPasswordConfigured(): Boolean
}

interface WebDavSyncSettingsMutation {
    suspend fun updateWebDavSyncEnabled(enabled: Boolean)

    suspend fun updateProvider(provider: WebDavProvider)

    suspend fun updateBaseUrl(url: String)

    suspend fun updateEndpointUrl(url: String)

    suspend fun updateUsername(username: String)

    suspend fun updatePassword(password: String)

    suspend fun updateAutoSyncEnabled(enabled: Boolean)

    suspend fun updateAutoSyncInterval(interval: String)

    suspend fun updateSyncOnRefreshEnabled(enabled: Boolean)
}

interface WebDavSyncSettingsActions {
    suspend fun triggerSyncNow()

    suspend fun testConnection(): WebDavSyncResult
}

class WebDavSyncSettingsUseCase
(
        webDavSyncRepository: WebDavSyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ) : WebDavSyncSettingsStateObservation by
        WebDavSyncSettingsStateObservationImpl(webDavSyncRepository),
    WebDavSyncCredentialObservation by
        WebDavSyncCredentialObservationImpl(webDavSyncRepository),
    WebDavSyncSettingsMutation by
        WebDavSyncSettingsMutationImpl(
            webDavSyncRepository = webDavSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
        ),
    WebDavSyncSettingsActions by
        WebDavSyncSettingsActionsImpl(
            webDavSyncRepository = webDavSyncRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

private class WebDavSyncSettingsStateObservationImpl(
    private val webDavSyncRepository: WebDavSyncRepository,
) : WebDavSyncSettingsStateObservation {
    private val shared =
        RemoteSyncSharedStateObservationImpl(
            enabled = webDavSyncRepository::isWebDavSyncEnabled,
            autoSyncEnabled = webDavSyncRepository::getAutoSyncEnabled,
            autoSyncInterval = webDavSyncRepository::getAutoSyncInterval,
            syncOnRefreshEnabled = webDavSyncRepository::getSyncOnRefreshEnabled,
            lastSyncTimeMillis = webDavSyncRepository::observeLastSyncTimeMillis,
            syncState = webDavSyncRepository::syncState,
        )

    override fun observeWebDavSyncEnabled(): Flow<Boolean> = shared.observeSyncEnabled()

    override fun observeProvider(): Flow<WebDavProvider> = webDavSyncRepository.getProvider()

    override fun observeBaseUrl(): Flow<String?> = webDavSyncRepository.getBaseUrl()

    override fun observeEndpointUrl(): Flow<String?> = webDavSyncRepository.getEndpointUrl()

    override fun observeUsername(): Flow<String?> = webDavSyncRepository.getUsername()

    override fun observeAutoSyncEnabled(): Flow<Boolean> = shared.observeAutoSyncEnabled()

    override fun observeAutoSyncInterval(): Flow<String> = shared.observeAutoSyncInterval()

    override fun observeSyncOnRefreshEnabled(): Flow<Boolean> = shared.observeSyncOnRefreshEnabled()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = shared.observeLastSyncTimeMillis()

    override fun observeSyncState(): Flow<WebDavSyncState> = shared.observeSyncState()
}

private class WebDavSyncCredentialObservationImpl(
    private val webDavSyncRepository: WebDavSyncRepository,
) : WebDavSyncCredentialObservation {
    override suspend fun getPasswordStatus(): StoredCredentialStatus = webDavSyncRepository.getPasswordStatus()

    override suspend fun getCredentialState(): CredentialState = webDavSyncRepository.getCredentialState()

    override suspend fun isPasswordConfigured(): Boolean = webDavSyncRepository.isPasswordConfigured()
}

private class WebDavSyncSettingsMutationImpl(
    private val webDavSyncRepository: WebDavSyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
) : WebDavSyncSettingsMutation {
    private val shared =
        RemoteSyncSharedMutationImpl(
            backendType = SyncBackendType.WEBDAV,
            syncPolicyRepository = syncPolicyRepository,
            autoSyncEnabledUpdater = webDavSyncRepository::setAutoSyncEnabled,
            autoSyncIntervalUpdater = webDavSyncRepository::setAutoSyncInterval,
            syncOnRefreshUpdater = webDavSyncRepository::setSyncOnRefreshEnabled,
        )

    override suspend fun updateWebDavSyncEnabled(enabled: Boolean) {
        shared.updateSyncEnabled(enabled)
    }

    override suspend fun updateProvider(provider: WebDavProvider) {
        webDavSyncRepository.setProvider(provider)
    }

    override suspend fun updateBaseUrl(url: String) {
        webDavSyncRepository.setBaseUrl(url)
    }

    override suspend fun updateEndpointUrl(url: String) {
        webDavSyncRepository.setEndpointUrl(url)
    }

    override suspend fun updateUsername(username: String) {
        webDavSyncRepository.setUsername(username)
    }

    override suspend fun updatePassword(password: String) {
        webDavSyncRepository.setPassword(password)
    }

    override suspend fun updateAutoSyncEnabled(enabled: Boolean) {
        shared.updateAutoSyncEnabled(enabled)
    }

    override suspend fun updateAutoSyncInterval(interval: String) {
        shared.updateAutoSyncInterval(interval)
    }

    override suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        shared.updateSyncOnRefreshEnabled(enabled)
    }
}

private class WebDavSyncSettingsActionsImpl(
    private val webDavSyncRepository: WebDavSyncRepository,
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
) : WebDavSyncSettingsActions {
    private val shared =
        RemoteSyncSharedActionsImpl(
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            connectionTester = webDavSyncRepository::testConnection,
        )

    override suspend fun triggerSyncNow() {
        shared.triggerSyncNow()
    }

    override suspend fun testConnection(): WebDavSyncResult = shared.testConnection()
}
