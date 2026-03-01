package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncErrorUseCase
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
    private val gitSyncRepo: GitSyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
    private val gitRemoteUrlUseCase: GitRemoteUrlUseCase,
    private val gitSyncErrorUseCase: GitSyncErrorUseCase,
    scope: CoroutineScope,
) {
    val gitSyncEnabled: StateFlow<Boolean> =
        gitSyncRepo
            .isGitSyncEnabled()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_SYNC_ENABLED,
            )

    val gitRemoteUrl: StateFlow<String> =
        gitSyncRepo
            .getRemoteUrl()
            .map { it ?: "" }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), "")

    private val _gitPatConfigured = MutableStateFlow(false)
    val gitPatConfigured: StateFlow<Boolean> = _gitPatConfigured.asStateFlow()

    val gitAuthorName: StateFlow<String> =
        gitSyncRepo
            .getAuthorName()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTHOR_NAME,
            )

    val gitAuthorEmail: StateFlow<String> =
        gitSyncRepo
            .getAuthorEmail()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTHOR_EMAIL,
            )

    val gitAutoSyncEnabled: StateFlow<Boolean> =
        gitSyncRepo
            .getAutoSyncEnabled()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTO_SYNC_ENABLED,
            )

    val gitAutoSyncInterval: StateFlow<String> =
        gitSyncRepo
            .getAutoSyncInterval()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_AUTO_SYNC_INTERVAL,
            )

    val gitSyncOnRefreshEnabled: StateFlow<Boolean> =
        gitSyncRepo
            .getSyncOnRefreshEnabled()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.GIT_SYNC_ON_REFRESH,
            )

    val gitLastSyncTime: StateFlow<Long> =
        gitSyncRepo
            .observeLastSyncTimeMillis()
            .map { value -> value ?: 0L }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0L)

    val gitSyncState: StateFlow<SyncEngineState> =
        gitSyncRepo
            .syncState()
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
            _gitPatConfigured.value = gitSyncRepo.getToken() != null
        }

    suspend fun updateGitSyncEnabled(enabled: Boolean): String? =
        runWithError("Failed to update Git sync setting") {
            gitSyncRepo.setGitSyncEnabled(enabled)
            syncPolicyRepository.applyGitSyncPolicy()
        }

    suspend fun updateGitRemoteUrl(url: String): String? =
        runWithError("Failed to update Git remote URL") {
            val normalized = gitRemoteUrlUseCase.normalize(url)
            gitSyncRepo.setRemoteUrl(normalized)
        }

    fun isValidGitRemoteUrl(url: String): Boolean = gitRemoteUrlUseCase.isValid(url)

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
            gitSyncRepo.setToken(token)
            _gitPatConfigured.value = token.isNotBlank()
        }

    suspend fun updateGitAuthorName(name: String): String? =
        runWithError("Failed to update Git author name") {
            gitSyncRepo.setAuthorInfo(name, gitAuthorEmail.value)
        }

    suspend fun updateGitAuthorEmail(email: String): String? =
        runWithError("Failed to update Git author email") {
            gitSyncRepo.setAuthorInfo(gitAuthorName.value, email)
        }

    suspend fun updateGitAutoSyncEnabled(enabled: Boolean): String? =
        runWithError("Failed to update Git auto-sync setting") {
            gitSyncRepo.setAutoSyncEnabled(enabled)
            syncPolicyRepository.applyGitSyncPolicy()
        }

    suspend fun updateGitAutoSyncInterval(interval: String): String? =
        runWithError("Failed to update Git auto-sync interval") {
            gitSyncRepo.setAutoSyncInterval(interval)
            syncPolicyRepository.applyGitSyncPolicy()
        }

    suspend fun updateGitSyncOnRefresh(enabled: Boolean): String? =
        runWithError("Failed to update Git sync-on-refresh setting") {
            gitSyncRepo.setSyncOnRefreshEnabled(enabled)
        }

    suspend fun triggerGitSyncNow(): String? =
        runWithError("Failed to run Git sync") {
            syncAndRebuildUseCase(forceSync = true)
        }

    suspend fun resolveGitConflictUsingRemote(): String? {
        return try {
            when (val resetResult = gitSyncRepo.resetLocalBranchToRemote()) {
                is GitSyncResult.Error -> {
                    sanitizeUserFacingMessage(
                        rawMessage = resetResult.message,
                        fallbackMessage = "Failed to resolve conflict with remote history",
                    )
                }

                else -> {
                    runWithError("Failed to resolve conflict with remote history") {
                        syncAndRebuildUseCase(forceSync = false)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            sanitizeUserFacingMessage(
                rawMessage = throwable.message,
                fallbackMessage = "Failed to resolve conflict with remote history",
            )
        }
    }

    suspend fun resolveGitConflictUsingLocal(): String? {
        return try {
            when (val result = gitSyncRepo.forcePushLocalToRemote()) {
                is GitSyncResult.Error -> {
                    sanitizeUserFacingMessage(
                        rawMessage = result.message,
                        fallbackMessage = "Failed to keep local changes during conflict resolution",
                    )
                }

                else -> {
                    runWithError("Failed to keep local changes during conflict resolution") {
                        syncAndRebuildUseCase(forceSync = false)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            sanitizeUserFacingMessage(
                rawMessage = throwable.message,
                fallbackMessage = "Failed to keep local changes during conflict resolution",
            )
        }
    }

    suspend fun testGitConnection(): String? {
        return try {
            _connectionTestState.value = SettingsGitConnectionTestState.Testing
            val result = gitSyncRepo.testConnection()
            _connectionTestState.value =
                when (result) {
                    is GitSyncResult.Success -> SettingsGitConnectionTestState.Success(result.message)
                    is GitSyncResult.Error ->
                        SettingsGitConnectionTestState.Error(
                            sanitizeUserFacingMessage(
                                rawMessage = result.message,
                                fallbackMessage = "Failed to test Git connection",
                            ),
                        )
                    else -> SettingsGitConnectionTestState.Error("Unexpected result")
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
    }

    fun resetConnectionTestState() {
        _connectionTestState.value = SettingsGitConnectionTestState.Idle
    }

    suspend fun resetGitRepository(): String? {
        _resetInProgress.value = true
        return try {
            when (val result = gitSyncRepo.resetRepository()) {
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
    ): String? {
        return try {
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
    }

    private fun sanitizeUserFacingMessage(
        rawMessage: String?,
        fallbackMessage: String,
    ): String =
        gitSyncErrorUseCase.sanitizeUserFacingMessage(
            rawMessage = rawMessage,
            fallbackMessage = fallbackMessage,
        )
}
