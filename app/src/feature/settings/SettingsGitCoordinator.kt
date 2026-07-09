package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class SettingsGitCoordinator(
    private val gitSyncSettingsUseCase: GitSyncSettingsUseCase,
    private val credentialCoordinator: SettingsCredentialCoordinator,
    scope: CoroutineScope,
) : SettingsGitFeatureSupport {
    private val sharedEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeGitSyncEnabled()
            .settingsStateIn(scope, PreferenceDefaults.GIT_SYNC_ENABLED)

    val gitRemoteUrl: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeRemoteUrl()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val gitPatStatus: StateFlow<StoredCredentialStatus> =
        credentialCoordinator
            .statusState(CredentialProvider.GIT, CredentialField.GIT_TOKEN)

    val gitPatConfigured: StateFlow<Boolean> =
        gitPatStatus.map { status -> status.isConfigured }.settingsStateIn(scope, false)

    val gitAuthorName: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAuthorName()
            .settingsStateIn(scope, PreferenceDefaults.GIT_AUTHOR_NAME)

    val gitAuthorEmail: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAuthorEmail()
            .settingsStateIn(scope, PreferenceDefaults.GIT_AUTHOR_EMAIL)

    private val sharedAutoSyncEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeAutoSyncEnabled()
            .settingsStateIn(scope, PreferenceDefaults.GIT_AUTO_SYNC_ENABLED)

    private val sharedAutoSyncInterval: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAutoSyncInterval()
            .settingsStateIn(scope, PreferenceDefaults.GIT_AUTO_SYNC_INTERVAL)

    private val sharedSyncOnRefreshEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeSyncOnRefreshEnabled()
            .settingsStateIn(scope, PreferenceDefaults.GIT_SYNC_ON_REFRESH)

    private val sharedLastSyncTime: StateFlow<Long> =
        gitSyncSettingsUseCase
            .observeLastSyncTimeMillis()
            .map { value -> value ?: 0L }
            .settingsStateIn(scope, 0L)

    private val _resetInProgress = MutableStateFlow(false)
    val resetInProgress: StateFlow<Boolean> = _resetInProgress.asStateFlow()

    val refreshPatConfigured: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to read Git token state") {
                credentialCoordinator.refreshCredentialState(CredentialProvider.GIT)
            }
        }

    private val updateGitSyncEnabledInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update Git sync setting") {
                gitSyncSettingsUseCase.updateGitSyncEnabled(enabled)
            }
        }

    val updateGitRemoteUrl: suspend (String) -> SettingsOperationError? =
        { url ->
            runWithError("Failed to update Git remote URL") {
                gitSyncSettingsUseCase.updateRemoteUrl(url)
            }
        }

    override val isValidGitRemoteUrl: (String) -> Boolean =
        { url -> gitSyncSettingsUseCase.isValidRemoteUrl(url) }

    override val shouldShowGitConflictDialog: (GitSyncErrorCode) -> Boolean =
        { code -> code == GitSyncErrorCode.CONFLICT }

    val updateGitPat: suspend (String) -> SettingsOperationError? =
        { token ->
            runWithError("Failed to update Git token") {
                credentialCoordinator.writeSecret(CredentialField.GIT_TOKEN, token)
            }
        }

    val updateGitAuthorName: suspend (String) -> SettingsOperationError? =
        { name ->
            runWithError("Failed to update Git author name") {
                gitSyncSettingsUseCase.updateAuthorInfo(name = name, email = gitAuthorEmail.value)
            }
        }

    val updateGitAuthorEmail: suspend (String) -> SettingsOperationError? =
        { email ->
            runWithError("Failed to update Git author email") {
                gitSyncSettingsUseCase.updateAuthorInfo(name = gitAuthorName.value, email = email)
            }
        }

    private val updateGitAutoSyncEnabledInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update Git auto-sync setting") {
                gitSyncSettingsUseCase.updateAutoSyncEnabled(enabled)
            }
        }

    private val updateGitAutoSyncIntervalInternal: suspend (String) -> SettingsOperationError? =
        { interval ->
            runWithError("Failed to update Git auto-sync interval") {
                gitSyncSettingsUseCase.updateAutoSyncInterval(interval)
            }
        }

    private val updateGitSyncOnRefreshInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update Git sync-on-refresh setting") {
                gitSyncSettingsUseCase.updateSyncOnRefreshEnabled(enabled)
            }
        }

    private val triggerGitSyncNowInternal: suspend () -> SettingsOperationError? =
        { runWithError("Failed to run Git sync") { gitSyncSettingsUseCase.triggerSyncNow() } }

    val resolveGitConflictUsingRemote: suspend () -> SettingsOperationError? =
        {
            gitSyncSettingsUseCase.resolveConflictUsingRemote().toOperationErrorOrNull()
        }

    val resolveGitConflictUsingLocal: suspend () -> SettingsOperationError? =
        {
            gitSyncSettingsUseCase.resolveConflictUsingLocal().toOperationErrorOrNull()
        }

    val resetGitRepository: suspend () -> SettingsOperationError? =
        {
            _resetInProgress.value = true
            try {
                runCatching {
                    gitSyncSettingsUseCase.resetRepository().toOperationErrorOrNull()
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    throwable.toGitOperationErrorOrNull()
                        ?: SettingsOperationError.Message(throwable.toUserMessage("Failed to reset Git repository"))
                }
            } finally {
                _resetInProgress.value = false
            }
        }

    private val credentialFields: StateFlow<List<RemoteProviderCredentialFieldState>> =
        gitPatStatus.mapSettingsStateIn(scope, emptyList()) { status ->
            listOf(
                RemoteProviderCredentialFieldState(
                    field = RemoteProviderCredentialField.GitPat,
                    status = status,
                ),
            )
        }

    private val providerSettingsController =
        ProviderSettingsController(
            provider = SyncBackendType.GIT,
            scope = scope,
            enabled = sharedEnabled,
            autoSyncEnabled = sharedAutoSyncEnabled,
            autoSyncInterval = sharedAutoSyncInterval,
            syncOnRefreshEnabled = sharedSyncOnRefreshEnabled,
            lastSyncTime = sharedLastSyncTime,
            credentialFields = credentialFields,
            rawSyncState = gitSyncSettingsUseCase.observeSyncState(),
            mapToUnifiedSyncState = { state -> state },
            updateEnabledAction = updateGitSyncEnabledInternal,
            updateAutoSyncEnabledAction = updateGitAutoSyncEnabledInternal,
            updateAutoSyncIntervalAction = updateGitAutoSyncIntervalInternal,
            updateSyncOnRefreshEnabledAction = updateGitSyncOnRefreshInternal,
            triggerSyncNowAction = triggerGitSyncNowInternal,
            testConnectionAction = ::testGitConnectionState,
            mapConnectionFailure = ::mapGitConnectionFailure,
        )

    val providerSettingsModel: StateFlow<RemoteProviderSettingsModel> = providerSettingsController.model
    val providerSettingsActions: RemoteProviderSettingsActionTarget = providerSettingsController
    val connectionTestState: StateFlow<RemoteProviderConnectionTestState> =
        providerSettingsController.connectionTestState
    val gitSyncEnabled: StateFlow<Boolean> = sharedEnabled
    val gitAutoSyncEnabled: StateFlow<Boolean> = sharedAutoSyncEnabled
    val gitAutoSyncInterval: StateFlow<String> = sharedAutoSyncInterval
    val gitSyncOnRefreshEnabled: StateFlow<Boolean> = sharedSyncOnRefreshEnabled
    val gitLastSyncTime: StateFlow<Long> = sharedLastSyncTime
    val gitSyncState: StateFlow<UnifiedSyncState> = providerSettingsController.syncState
    val updateGitSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        providerSettingsController::updateEnabled
    val updateGitAutoSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        providerSettingsController::updateAutoSyncEnabled
    val updateGitAutoSyncInterval: suspend (String) -> SettingsOperationError? =
        providerSettingsController::updateAutoSyncInterval
    val updateGitSyncOnRefresh: suspend (Boolean) -> SettingsOperationError? =
        providerSettingsController::updateSyncOnRefreshEnabled
    val triggerGitSyncNow: suspend () -> SettingsOperationError? = providerSettingsController::triggerSyncNow
    val testGitConnection: suspend () -> SettingsOperationError? = providerSettingsController::testConnection

    override val resetConnectionTestState: () -> Unit = providerSettingsController::resetConnectionTestState

    private suspend fun testGitConnectionState(): RemoteProviderConnectionTestState =
        when (val result = gitSyncSettingsUseCase.testConnection()) {
            is GitSyncResult.Success -> RemoteProviderConnectionTestState.Success(result.message)
            is GitSyncResult.Error ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = result.code.name,
                    detail = result.message,
                )
            GitSyncResult.NotConfigured ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = GitSyncErrorCode.NOT_CONFIGURED.name,
                    detail = "Git sync is not configured",
                )
            GitSyncResult.DirectPathRequired ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = GitSyncErrorCode.DIRECT_PATH_REQUIRED.name,
                    detail = "Git sync requires a direct local directory path",
                )
            is GitSyncResult.Conflict ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = GitSyncErrorCode.CONFLICT.name,
                    detail = result.message,
                )
        }

    private fun mapGitConnectionFailure(throwable: Throwable): RemoteProviderConnectionTestState.Error {
        val operationError =
            throwable.toGitOperationErrorOrNull()
                ?: SettingsOperationError.Message(throwable.toUserMessage("Failed to test Git connection"))
        return when (operationError) {
            is SettingsOperationError.GitSync ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = operationError.code.name,
                    detail = operationError.detail,
                )
            is SettingsOperationError.Message ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = GitSyncErrorCode.UNKNOWN.name,
                    detail = operationError.text,
                )
            is SettingsOperationError.WebDavSync ->
                RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.GIT,
                    providerCode = GitSyncErrorCode.UNKNOWN.name,
                    detail = operationError.detail,
                )
        }
    }

    private suspend fun runWithError(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ): SettingsOperationError? =
        runSettingsOperation(
            fallbackMessage = fallbackMessage,
            specificError = { throwable -> throwable.toGitOperationErrorOrNull() },
            action = action,
        )
}

private fun GitSyncResult.Error.toOperationError(): SettingsOperationError.GitSync =
    SettingsOperationError.GitSync(code = code, detail = message)

private fun GitSyncResult.toOperationErrorOrNull(): SettingsOperationError.GitSync? =
    when (this) {
        is GitSyncResult.Error -> toOperationError()
        GitSyncResult.NotConfigured ->
            SettingsOperationError.GitSync(
                code = GitSyncErrorCode.NOT_CONFIGURED,
                detail = "Git sync is not configured",
            )
        GitSyncResult.DirectPathRequired ->
            SettingsOperationError.GitSync(
                code = GitSyncErrorCode.DIRECT_PATH_REQUIRED,
                detail = "Git sync requires a direct local directory path",
            )
        is GitSyncResult.Conflict ->
            SettingsOperationError.GitSync(
                code = GitSyncErrorCode.CONFLICT,
                detail = message,
            )
        is GitSyncResult.Success -> null
    }

private fun Throwable.toGitOperationErrorOrNull(): SettingsOperationError.GitSync? =
    when (this) {
        is GitSyncFailureException ->
            SettingsOperationError.GitSync(code = code, detail = message)
        else -> null
    }
