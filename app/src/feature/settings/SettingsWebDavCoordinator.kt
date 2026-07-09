package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import com.lomo.domain.usecase.toUnifiedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class SettingsWebDavCoordinator(
    private val webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase,
    private val credentialCoordinator: SettingsCredentialCoordinator,
    scope: CoroutineScope,
) : SettingsWebDavFeatureSupport {
    private val sharedEnabled: StateFlow<Boolean> =
        webDavSyncSettingsUseCase
            .observeWebDavSyncEnabled()
            .settingsStateIn(scope, PreferenceDefaults.WEBDAV_SYNC_ENABLED)

    val webDavProvider: StateFlow<WebDavProvider> =
        webDavSyncSettingsUseCase
            .observeProvider()
            .settingsStateIn(scope, WebDavProvider.NUTSTORE)

    val webDavBaseUrl: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeBaseUrl()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val webDavEndpointUrl: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeEndpointUrl()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val webDavUsername: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeUsername()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val passwordStatus: StateFlow<StoredCredentialStatus> =
        credentialCoordinator
            .statusState(CredentialProvider.WEBDAV, CredentialField.WEBDAV_PASSWORD)

    val passwordConfigured: StateFlow<Boolean> =
        passwordStatus.map { status -> status.isConfigured }.settingsStateIn(scope, false)

    private val sharedAutoSyncEnabled: StateFlow<Boolean> =
        webDavSyncSettingsUseCase
            .observeAutoSyncEnabled()
            .settingsStateIn(scope, PreferenceDefaults.WEBDAV_AUTO_SYNC_ENABLED)

    private val sharedAutoSyncInterval: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeAutoSyncInterval()
            .settingsStateIn(scope, PreferenceDefaults.WEBDAV_AUTO_SYNC_INTERVAL)

    private val sharedSyncOnRefreshEnabled: StateFlow<Boolean> =
        webDavSyncSettingsUseCase
            .observeSyncOnRefreshEnabled()
            .settingsStateIn(scope, PreferenceDefaults.WEBDAV_SYNC_ON_REFRESH)

    private val sharedLastSyncTime: StateFlow<Long> =
        webDavSyncSettingsUseCase
            .observeLastSyncTimeMillis()
            .map { it ?: 0L }
            .settingsStateIn(scope, 0L)

    val refreshPasswordConfigured: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to read WebDAV password state") {
                credentialCoordinator.refreshCredentialState(CredentialProvider.WEBDAV)
            }
        }

    private val updateWebDavSyncEnabledInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update WebDAV sync setting") {
                webDavSyncSettingsUseCase.updateWebDavSyncEnabled(enabled)
            }
        }

    val updateWebDavProvider: suspend (WebDavProvider) -> SettingsOperationError? =
        { provider ->
            runWithError("Failed to update WebDAV provider") {
                webDavSyncSettingsUseCase.updateProvider(provider)
            }
        }

    val updateWebDavBaseUrl: suspend (String) -> SettingsOperationError? =
        { url ->
            runWithError("Failed to update WebDAV base URL") {
                webDavSyncSettingsUseCase.updateBaseUrl(url)
            }
        }

    val updateWebDavEndpointUrl: suspend (String) -> SettingsOperationError? =
        { url ->
            runWithError("Failed to update WebDAV endpoint") {
                webDavSyncSettingsUseCase.updateEndpointUrl(url)
            }
        }

    val updateWebDavUsername: suspend (String) -> SettingsOperationError? =
        { username ->
            runWithError("Failed to update WebDAV username") {
                webDavSyncSettingsUseCase.updateUsername(username)
            }
        }

    val updateWebDavPassword: suspend (String) -> SettingsOperationError? =
        { password ->
            runWithError("Failed to update WebDAV password") {
                webDavSyncSettingsUseCase.updatePassword(password)
                credentialCoordinator.writeSecret(CredentialField.WEBDAV_PASSWORD, password)
            }
        }

    private val updateWebDavAutoSyncEnabledInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update WebDAV auto-sync setting") {
                webDavSyncSettingsUseCase.updateAutoSyncEnabled(enabled)
            }
        }

    private val updateWebDavAutoSyncIntervalInternal: suspend (String) -> SettingsOperationError? =
        { interval ->
            runWithError("Failed to update WebDAV auto-sync interval") {
                webDavSyncSettingsUseCase.updateAutoSyncInterval(interval)
            }
        }

    private val updateWebDavSyncOnRefreshInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update WebDAV sync-on-refresh setting") {
                webDavSyncSettingsUseCase.updateSyncOnRefreshEnabled(enabled)
            }
        }

    private val triggerWebDavSyncNowInternal: suspend () -> SettingsOperationError? =
        { runWithError("Failed to run WebDAV sync") { webDavSyncSettingsUseCase.triggerSyncNow() } }

    private val credentialFields: StateFlow<List<RemoteProviderCredentialFieldState>> =
        passwordStatus.mapSettingsStateIn(scope, emptyList()) { status ->
            listOf(
                RemoteProviderCredentialFieldState(
                    field = RemoteProviderCredentialField.WebDavPassword,
                    status = status,
                ),
            )
        }

    private val providerSettingsController =
        ProviderSettingsController(
            provider = SyncBackendType.WEBDAV,
            scope = scope,
            enabled = sharedEnabled,
            autoSyncEnabled = sharedAutoSyncEnabled,
            autoSyncInterval = sharedAutoSyncInterval,
            syncOnRefreshEnabled = sharedSyncOnRefreshEnabled,
            lastSyncTime = sharedLastSyncTime,
            credentialFields = credentialFields,
            rawSyncState = webDavSyncSettingsUseCase.observeSyncState(),
            mapToUnifiedSyncState = { state -> state.toUnifiedState(SyncBackendType.WEBDAV) },
            updateEnabledAction = updateWebDavSyncEnabledInternal,
            updateAutoSyncEnabledAction = updateWebDavAutoSyncEnabledInternal,
            updateAutoSyncIntervalAction = updateWebDavAutoSyncIntervalInternal,
            updateSyncOnRefreshEnabledAction = updateWebDavSyncOnRefreshInternal,
            triggerSyncNowAction = triggerWebDavSyncNowInternal,
            testConnectionAction = ::testWebDavConnectionState,
            mapConnectionFailure = ::mapWebDavConnectionFailure,
        )

    val providerSettingsModel: StateFlow<RemoteProviderSettingsModel> = providerSettingsController.model
    val providerSettingsActions: RemoteProviderSettingsActionTarget = providerSettingsController
    val connectionTestState: StateFlow<RemoteProviderConnectionTestState> =
        providerSettingsController.connectionTestState
    val webDavSyncEnabled: StateFlow<Boolean> = sharedEnabled
    val webDavAutoSyncEnabled: StateFlow<Boolean> = sharedAutoSyncEnabled
    val webDavAutoSyncInterval: StateFlow<String> = sharedAutoSyncInterval
    val webDavSyncOnRefreshEnabled: StateFlow<Boolean> = sharedSyncOnRefreshEnabled
    val webDavLastSyncTime: StateFlow<Long> = sharedLastSyncTime
    val webDavSyncState: StateFlow<UnifiedSyncState> = providerSettingsController.syncState
    val updateWebDavSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        providerSettingsController::updateEnabled
    val updateWebDavAutoSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        providerSettingsController::updateAutoSyncEnabled
    val updateWebDavAutoSyncInterval: suspend (String) -> SettingsOperationError? =
        providerSettingsController::updateAutoSyncInterval
    val updateWebDavSyncOnRefresh: suspend (Boolean) -> SettingsOperationError? =
        providerSettingsController::updateSyncOnRefreshEnabled
    val triggerWebDavSyncNow: suspend () -> SettingsOperationError? = providerSettingsController::triggerSyncNow
    val testWebDavConnection: suspend () -> SettingsOperationError? = providerSettingsController::testConnection

    override fun resetConnectionTestState() {
        providerSettingsController.resetConnectionTestState()
    }

    private suspend fun testWebDavConnectionState(): RemoteProviderConnectionTestState =
        when (val result = webDavSyncSettingsUseCase.testConnection()) {
            is WebDavSyncResult.Success -> RemoteProviderConnectionTestState.Success(result.message)
            is WebDavSyncResult.Error ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = result.code.name,
                    detail = result.message,
                )
            is WebDavSyncResult.Conflict ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = WebDavSyncErrorCode.UNKNOWN.name,
                    detail = "WebDAV sync conflict detected",
                )
            is WebDavSyncResult.Review ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = WebDavSyncErrorCode.UNKNOWN.name,
                    detail = result.message.ifBlank { "WebDAV sync review required" },
                )
            WebDavSyncResult.NotConfigured ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = WebDavSyncErrorCode.NOT_CONFIGURED.name,
                    detail = "WebDAV sync is not configured",
                )
        }

    private fun mapWebDavConnectionFailure(throwable: Throwable): RemoteProviderConnectionTestState.Error {
        val operationError =
            throwable.toWebDavOperationErrorOrNull()
                ?: SettingsOperationError.Message(throwable.toUserMessage("Failed to test WebDAV connection"))
        return when (operationError) {
            is SettingsOperationError.WebDavSync ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = operationError.code.name,
                    detail = operationError.detail,
                )
            is SettingsOperationError.Message ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = WebDavSyncErrorCode.UNKNOWN.name,
                    detail = operationError.text,
                )
            is SettingsOperationError.GitSync ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.WEBDAV,
                    providerCode = WebDavSyncErrorCode.UNKNOWN.name,
                    detail = operationError.detail,
                )
        }
    }

    override fun isValidWebDavUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("https://") || trimmed.startsWith("http://") || !trimmed.contains("://")
    }

    private suspend fun runWithError(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ): SettingsOperationError? =
        runSettingsOperation(
            fallbackMessage = fallbackMessage,
            specificError = { throwable -> throwable.toWebDavOperationErrorOrNull() },
            action = action,
        )

}

private fun Throwable.toWebDavOperationErrorOrNull(): SettingsOperationError.WebDavSync? =
    when (this) {
        is WebDavSyncFailureException ->
            SettingsOperationError.WebDavSync(code = code, detail = message)
        else -> null
    }

private fun WebDavSyncResult.toOperationErrorOrNull(): SettingsOperationError.WebDavSync? =
    when (this) {
        is WebDavSyncResult.Error ->
            SettingsOperationError.WebDavSync(code = code, detail = message)
        WebDavSyncResult.NotConfigured ->
            SettingsOperationError.WebDavSync(
                code = WebDavSyncErrorCode.NOT_CONFIGURED,
                detail = "WebDAV sync is not configured",
            )
        is WebDavSyncResult.Conflict ->
            SettingsOperationError.WebDavSync(code = WebDavSyncErrorCode.UNKNOWN, detail = message)
        is WebDavSyncResult.Review ->
            SettingsOperationError.WebDavSync(code = WebDavSyncErrorCode.UNKNOWN, detail = message)
        is WebDavSyncResult.Success -> null
    }
