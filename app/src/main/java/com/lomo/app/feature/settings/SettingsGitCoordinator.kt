package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface SettingsGitConnectionTestState {
    data object Idle : SettingsGitConnectionTestState

    data object Testing : SettingsGitConnectionTestState

    data class Success(
        val message: String,
    ) : SettingsGitConnectionTestState

    data class Error(
        val code: GitSyncErrorCode,
        val detail: String? = null,
    ) : SettingsGitConnectionTestState
}

class SettingsGitCoordinator(
    private val gitSyncSettingsUseCase: GitSyncSettingsUseCase,
    scope: CoroutineScope,
) : SettingsGitFeatureSupport {
    val gitSyncEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeGitSyncEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.GIT_SYNC_ENABLED,
            )

    val gitRemoteUrl: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeRemoteUrl()
            .map { it ?: "" }
            .stateIn(scope, settingsWhileSubscribed(), "")

    private val _gitPatConfigured = MutableStateFlow(false)
    val gitPatConfigured: StateFlow<Boolean> = _gitPatConfigured.asStateFlow()

    val gitAuthorName: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAuthorName()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.GIT_AUTHOR_NAME,
            )

    val gitAuthorEmail: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAuthorEmail()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.GIT_AUTHOR_EMAIL,
            )

    val gitAutoSyncEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeAutoSyncEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.GIT_AUTO_SYNC_ENABLED,
            )

    val gitAutoSyncInterval: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAutoSyncInterval()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.GIT_AUTO_SYNC_INTERVAL,
            )

    val gitSyncOnRefreshEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeSyncOnRefreshEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.GIT_SYNC_ON_REFRESH,
            )

    val gitLastSyncTime: StateFlow<Long> =
        gitSyncSettingsUseCase
            .observeLastSyncTimeMillis()
            .map { value -> value ?: 0L }
            .stateIn(scope, settingsWhileSubscribed(), 0L)

    val gitSyncState: StateFlow<SyncEngineState> =
        gitSyncSettingsUseCase
            .observeSyncState()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                SyncEngineState.Idle,
            )

    private val _connectionTestState =
        MutableStateFlow<SettingsGitConnectionTestState>(SettingsGitConnectionTestState.Idle)
    val connectionTestState: StateFlow<SettingsGitConnectionTestState> = _connectionTestState.asStateFlow()

    private val _resetInProgress = MutableStateFlow(false)
    val resetInProgress: StateFlow<Boolean> = _resetInProgress.asStateFlow()

    val refreshPatConfigured: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to read Git token state") {
                _gitPatConfigured.value = gitSyncSettingsUseCase.isTokenConfigured()
            }
        }

    val updateGitSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
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
                gitSyncSettingsUseCase.updateToken(token)
                _gitPatConfigured.value = token.isNotBlank()
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

    val updateGitAutoSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update Git auto-sync setting") {
                gitSyncSettingsUseCase.updateAutoSyncEnabled(enabled)
            }
        }

    val updateGitAutoSyncInterval: suspend (String) -> SettingsOperationError? =
        { interval ->
            runWithError("Failed to update Git auto-sync interval") {
                gitSyncSettingsUseCase.updateAutoSyncInterval(interval)
            }
        }

    val updateGitSyncOnRefresh: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update Git sync-on-refresh setting") {
                gitSyncSettingsUseCase.updateSyncOnRefreshEnabled(enabled)
            }
        }

    val triggerGitSyncNow: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to run Git sync") {
                gitSyncSettingsUseCase.triggerSyncNow()
            }
        }

    val resolveGitConflictUsingRemote: suspend () -> SettingsOperationError? =
        {
            gitSyncSettingsUseCase.resolveConflictUsingRemote().toOperationErrorOrNull()
        }

    val resolveGitConflictUsingLocal: suspend () -> SettingsOperationError? =
        {
            gitSyncSettingsUseCase.resolveConflictUsingLocal().toOperationErrorOrNull()
        }

    val testGitConnection: suspend () -> SettingsOperationError? =
        {
            runCatching {
                _connectionTestState.value = SettingsGitConnectionTestState.Testing
                val result = gitSyncSettingsUseCase.testConnection()
                _connectionTestState.value =
                    when (result) {
                        is GitSyncResult.Success -> {
                            SettingsGitConnectionTestState.Success(result.message)
                        }

                        is GitSyncResult.Error -> {
                            SettingsGitConnectionTestState.Error(result.code, result.message)
                        }

                        GitSyncResult.NotConfigured -> {
                            SettingsGitConnectionTestState.Error(
                                code = GitSyncErrorCode.NOT_CONFIGURED,
                                detail = "Git sync is not configured",
                            )
                        }

                        GitSyncResult.DirectPathRequired -> {
                            SettingsGitConnectionTestState.Error(
                                code = GitSyncErrorCode.DIRECT_PATH_REQUIRED,
                                detail = "Git sync requires a direct local directory path",
                            )
                        }

                        is GitSyncResult.Conflict ->
                            SettingsGitConnectionTestState.Error(
                                code = GitSyncErrorCode.CONFLICT,
                                detail = result.message,
                            )
                    }
                null
            }.getOrElse { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                val operationError =
                    throwable.toGitOperationErrorOrNull()
                        ?: SettingsOperationError.Message(throwable.toUserMessage("Failed to test Git connection"))
                _connectionTestState.value =
                    when (operationError) {
                        is SettingsOperationError.GitSync ->
                            SettingsGitConnectionTestState.Error(operationError.code, operationError.detail)
                        is SettingsOperationError.Message ->
                            SettingsGitConnectionTestState.Error(GitSyncErrorCode.UNKNOWN, operationError.text)
                        is SettingsOperationError.WebDavSync ->
                            SettingsGitConnectionTestState.Error(GitSyncErrorCode.UNKNOWN, operationError.detail)
                    }
                null
            }
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

    override val resetConnectionTestState: () -> Unit =
        { _connectionTestState.value = SettingsGitConnectionTestState.Idle }

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
            throwable.toGitOperationErrorOrNull()
                ?: SettingsOperationError.Message(throwable.toUserMessage(fallbackMessage))
        }
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
