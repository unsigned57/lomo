package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncFailureException
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import com.lomo.domain.usecase.toUnifiedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface SettingsWebDavConnectionTestState {
    data object Idle : SettingsWebDavConnectionTestState

    data object Testing : SettingsWebDavConnectionTestState

    data class Success(
        val message: String,
    ) : SettingsWebDavConnectionTestState

    data class Error(
        val code: WebDavSyncErrorCode,
        val detail: String? = null,
    ) : SettingsWebDavConnectionTestState
}

class SettingsWebDavCoordinator(
    private val webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase,
    scope: CoroutineScope,
) : SettingsWebDavFeatureSupport {
    val webDavSyncEnabled: StateFlow<Boolean> =
        webDavSyncSettingsUseCase
            .observeWebDavSyncEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.WEBDAV_SYNC_ENABLED,
            )

    val webDavProvider: StateFlow<WebDavProvider> =
        webDavSyncSettingsUseCase
            .observeProvider()
            .stateIn(scope, settingsWhileSubscribed(), WebDavProvider.NUTSTORE)

    val webDavBaseUrl: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeBaseUrl()
            .map { it ?: "" }
            .stateIn(scope, settingsWhileSubscribed(), "")

    val webDavEndpointUrl: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeEndpointUrl()
            .map { it ?: "" }
            .stateIn(scope, settingsWhileSubscribed(), "")

    val webDavUsername: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeUsername()
            .map { it ?: "" }
            .stateIn(scope, settingsWhileSubscribed(), "")

    private val _passwordConfigured = MutableStateFlow(false)
    val passwordConfigured: StateFlow<Boolean> = _passwordConfigured.asStateFlow()

    val webDavAutoSyncEnabled: StateFlow<Boolean> =
        webDavSyncSettingsUseCase
            .observeAutoSyncEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.WEBDAV_AUTO_SYNC_ENABLED,
            )

    val webDavAutoSyncInterval: StateFlow<String> =
        webDavSyncSettingsUseCase
            .observeAutoSyncInterval()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.WEBDAV_AUTO_SYNC_INTERVAL,
            )

    val webDavSyncOnRefreshEnabled: StateFlow<Boolean> =
        webDavSyncSettingsUseCase
            .observeSyncOnRefreshEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.WEBDAV_SYNC_ON_REFRESH,
            )

    val webDavLastSyncTime: StateFlow<Long> =
        webDavSyncSettingsUseCase
            .observeLastSyncTimeMillis()
            .map { it ?: 0L }
            .stateIn(scope, settingsWhileSubscribed(), 0L)

    val webDavSyncState: StateFlow<UnifiedSyncState> =
        webDavSyncSettingsUseCase
            .observeSyncState()
            .map { state -> state.toUnifiedState(SyncBackendType.WEBDAV) }
            .stateIn(scope, settingsWhileSubscribed(), UnifiedSyncState.Idle)

    private val _connectionTestState =
        MutableStateFlow<SettingsWebDavConnectionTestState>(SettingsWebDavConnectionTestState.Idle)
    val connectionTestState: StateFlow<SettingsWebDavConnectionTestState> = _connectionTestState.asStateFlow()

    val refreshPasswordConfigured: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to read WebDAV password state") {
                _passwordConfigured.value = webDavSyncSettingsUseCase.isPasswordConfigured()
            }
        }

    val updateWebDavSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
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
                _passwordConfigured.value = password.isNotBlank()
            }
        }

    val updateWebDavAutoSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update WebDAV auto-sync setting") {
                webDavSyncSettingsUseCase.updateAutoSyncEnabled(enabled)
            }
        }

    val updateWebDavAutoSyncInterval: suspend (String) -> SettingsOperationError? =
        { interval ->
            runWithError("Failed to update WebDAV auto-sync interval") {
                webDavSyncSettingsUseCase.updateAutoSyncInterval(interval)
            }
        }

    val updateWebDavSyncOnRefresh: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update WebDAV sync-on-refresh setting") {
                webDavSyncSettingsUseCase.updateSyncOnRefreshEnabled(enabled)
            }
        }

    val triggerWebDavSyncNow: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to run WebDAV sync") {
                webDavSyncSettingsUseCase.triggerSyncNow()
            }
        }

    val testWebDavConnection: suspend () -> SettingsOperationError? =
        {
            runCatching {
                _connectionTestState.value = SettingsWebDavConnectionTestState.Testing
                val result = webDavSyncSettingsUseCase.testConnection()
                _connectionTestState.value =
                    when (result) {
                        is WebDavSyncResult.Success -> {
                            SettingsWebDavConnectionTestState.Success(result.message)
                        }

                        is WebDavSyncResult.Error -> {
                            SettingsWebDavConnectionTestState.Error(result.code, result.message)
                        }

                        is WebDavSyncResult.Conflict -> {
                            SettingsWebDavConnectionTestState.Error(
                                code = WebDavSyncErrorCode.UNKNOWN,
                                detail = "WebDAV sync conflict detected",
                            )
                        }

                        WebDavSyncResult.NotConfigured -> {
                            SettingsWebDavConnectionTestState.Error(
                                code = WebDavSyncErrorCode.NOT_CONFIGURED,
                                detail = "WebDAV sync is not configured",
                            )
                        }
                    }
                null
            }.getOrElse { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                val operationError =
                    throwable.toWebDavOperationErrorOrNull()
                        ?: SettingsOperationError.Message(throwable.toUserMessage("Failed to test WebDAV connection"))
                _connectionTestState.value =
                    when (operationError) {
                        is SettingsOperationError.WebDavSync ->
                            SettingsWebDavConnectionTestState.Error(operationError.code, operationError.detail)
                        is SettingsOperationError.Message ->
                            SettingsWebDavConnectionTestState.Error(WebDavSyncErrorCode.UNKNOWN, operationError.text)
                        is SettingsOperationError.GitSync ->
                            SettingsWebDavConnectionTestState.Error(WebDavSyncErrorCode.UNKNOWN, operationError.detail)
                    }
                null
            }
        }

    override fun resetConnectionTestState() {
        _connectionTestState.value = SettingsWebDavConnectionTestState.Idle
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
        runCatching {
            action()
            null
        }.getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            throwable.toWebDavOperationErrorOrNull()
                ?: SettingsOperationError.Message(throwable.toUserMessage(fallbackMessage))
        }
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
        is WebDavSyncResult.Success -> null
    }
