package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.usecase.GitSyncErrorUseCase
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
        val message: String,
    ) : SettingsGitConnectionTestState
}

class SettingsGitCoordinator(
    private val gitSyncSettingsUseCase: GitSyncSettingsUseCase,
    private val gitSyncErrorUseCase: GitSyncErrorUseCase,
    scope: CoroutineScope,
) {
    val gitSyncEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeGitSyncEnabled()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_SYNC_ENABLED,
            )

    val gitRemoteUrl: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeRemoteUrl()
            .map { it ?: "" }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), "")

    private val _gitPatConfigured = MutableStateFlow(false)
    val gitPatConfigured: StateFlow<Boolean> = _gitPatConfigured.asStateFlow()

    val gitAuthorName: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAuthorName()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTHOR_NAME,
            )

    val gitAuthorEmail: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAuthorEmail()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTHOR_EMAIL,
            )

    val gitAutoSyncEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeAutoSyncEnabled()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTO_SYNC_ENABLED,
            )

    val gitAutoSyncInterval: StateFlow<String> =
        gitSyncSettingsUseCase
            .observeAutoSyncInterval()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTO_SYNC_INTERVAL,
            )

    val gitSyncOnRefreshEnabled: StateFlow<Boolean> =
        gitSyncSettingsUseCase
            .observeSyncOnRefreshEnabled()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_SYNC_ON_REFRESH,
            )

    val gitLastSyncTime: StateFlow<Long> =
        gitSyncSettingsUseCase
            .observeLastSyncTimeMillis()
            .map { value -> value ?: 0L }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0L)

    val gitSyncState: StateFlow<SyncEngineState> =
        gitSyncSettingsUseCase
            .observeSyncState()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                SyncEngineState.Idle,
            )

    private val _connectionTestState = MutableStateFlow<SettingsGitConnectionTestState>(SettingsGitConnectionTestState.Idle)
    val connectionTestState: StateFlow<SettingsGitConnectionTestState> = _connectionTestState.asStateFlow()

    private val _resetInProgress = MutableStateFlow(false)
    val resetInProgress: StateFlow<Boolean> = _resetInProgress.asStateFlow()

    suspend fun refreshPatConfigured(): String? =
        runWithError("Failed to read Git token state") {
            _gitPatConfigured.value = gitSyncSettingsUseCase.isTokenConfigured()
        }

    suspend fun updateGitSyncEnabled(enabled: Boolean): String? =
        runWithError("Failed to update Git sync setting") {
            gitSyncSettingsUseCase.updateGitSyncEnabled(enabled)
        }

    suspend fun updateGitRemoteUrl(url: String): String? =
        runWithError("Failed to update Git remote URL") {
            gitSyncSettingsUseCase.updateRemoteUrl(url)
        }

    fun isValidGitRemoteUrl(url: String): Boolean = gitSyncSettingsUseCase.isValidRemoteUrl(url)

    fun shouldShowGitConflictDialog(message: String): Boolean =
        gitSyncErrorUseCase.classify(message) == GitSyncErrorUseCase.ErrorKind.CONFLICT

    fun presentGitSyncErrorMessage(
        message: String,
        conflictSummary: String,
        directPathRequired: String,
        unknownError: String,
    ): String =
        when (gitSyncErrorUseCase.classify(message)) {
            GitSyncErrorUseCase.ErrorKind.CONFLICT -> conflictSummary

            GitSyncErrorUseCase.ErrorKind.DIRECT_PATH_REQUIRED -> directPathRequired

            GitSyncErrorUseCase.ErrorKind.TECHNICAL,
            GitSyncErrorUseCase.ErrorKind.EMPTY,
            -> unknownError

            GitSyncErrorUseCase.ErrorKind.USER_FACING -> message
        }

    suspend fun updateGitPat(token: String): String? =
        runWithError("Failed to update Git token") {
            gitSyncSettingsUseCase.updateToken(token)
            _gitPatConfigured.value = token.isNotBlank()
        }

    suspend fun updateGitAuthorName(name: String): String? =
        runWithError("Failed to update Git author name") {
            gitSyncSettingsUseCase.updateAuthorInfo(name = name, email = gitAuthorEmail.value)
        }

    suspend fun updateGitAuthorEmail(email: String): String? =
        runWithError("Failed to update Git author email") {
            gitSyncSettingsUseCase.updateAuthorInfo(name = gitAuthorName.value, email = email)
        }

    suspend fun updateGitAutoSyncEnabled(enabled: Boolean): String? =
        runWithError("Failed to update Git auto-sync setting") {
            gitSyncSettingsUseCase.updateAutoSyncEnabled(enabled)
        }

    suspend fun updateGitAutoSyncInterval(interval: String): String? =
        runWithError("Failed to update Git auto-sync interval") {
            gitSyncSettingsUseCase.updateAutoSyncInterval(interval)
        }

    suspend fun updateGitSyncOnRefresh(enabled: Boolean): String? =
        runWithError("Failed to update Git sync-on-refresh setting") {
            gitSyncSettingsUseCase.updateSyncOnRefreshEnabled(enabled)
        }

    suspend fun triggerGitSyncNow(): String? =
        runWithError("Failed to run Git sync") {
            gitSyncSettingsUseCase.triggerSyncNow()
        }

    suspend fun resolveGitConflictUsingRemote(): String? =
        when (val result = gitSyncSettingsUseCase.resolveConflictUsingRemote()) {
            is GitSyncResult.Error -> {
                sanitizeUserFacingMessage(
                    rawMessage = result.message,
                    fallbackMessage = "Failed to resolve conflict with remote history",
                )
            }

            else -> {
                null
            }
        }

    suspend fun resolveGitConflictUsingLocal(): String? =
        when (val result = gitSyncSettingsUseCase.resolveConflictUsingLocal()) {
            is GitSyncResult.Error -> {
                sanitizeUserFacingMessage(
                    rawMessage = result.message,
                    fallbackMessage = "Failed to keep local changes during conflict resolution",
                )
            }

            else -> {
                null
            }
        }

    suspend fun testGitConnection(): String? =
        try {
            _connectionTestState.value = SettingsGitConnectionTestState.Testing
            val result = gitSyncSettingsUseCase.testConnection()
            _connectionTestState.value =
                when (result) {
                    is GitSyncResult.Success -> {
                        SettingsGitConnectionTestState.Success(result.message)
                    }

                    is GitSyncResult.Error -> {
                        SettingsGitConnectionTestState.Error(
                            sanitizeUserFacingMessage(
                                rawMessage = result.message,
                                fallbackMessage = "Failed to test Git connection",
                            ),
                        )
                    }

                    else -> {
                        SettingsGitConnectionTestState.Error("Unexpected result")
                    }
                }
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val message =
                sanitizeUserFacingMessage(
                    rawMessage = throwable.message,
                    fallbackMessage = "Failed to test Git connection",
                )
            _connectionTestState.value = SettingsGitConnectionTestState.Error(message)
            message
        }

    fun resetConnectionTestState() {
        _connectionTestState.value = SettingsGitConnectionTestState.Idle
    }

    suspend fun resetGitRepository(): String? {
        _resetInProgress.value = true
        return try {
            when (val result = gitSyncSettingsUseCase.resetRepository()) {
                is GitSyncResult.Error -> {
                    sanitizeUserFacingMessage(
                        rawMessage = result.message,
                        fallbackMessage = "Failed to reset Git repository",
                    )
                }

                else -> {
                    null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            sanitizeUserFacingMessage(
                rawMessage = throwable.message,
                fallbackMessage = "Failed to reset Git repository",
            )
        } finally {
            _resetInProgress.value = false
        }
    }

    private suspend fun runWithError(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ): String? =
        try {
            action()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            sanitizeUserFacingMessage(
                rawMessage = throwable.message,
                fallbackMessage = fallbackMessage,
            )
        }

    private fun sanitizeUserFacingMessage(
        rawMessage: String?,
        fallbackMessage: String,
    ): String {
        val normalized =
            rawMessage
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
        return normalized.takeIf { it.isNotBlank() && !it.contains("Exception") } ?: fallbackMessage
    }
}
