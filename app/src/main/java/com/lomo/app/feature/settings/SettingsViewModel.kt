package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.SyncSchedulerRepository
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.util.PreferenceDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val appConfigRepository: AppConfigRepository,
        private val shareServiceManager: LanShareService,
        private val gitSyncRepo: GitSyncRepository,
        private val syncSchedulerRepository: SyncSchedulerRepository,
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ) : ViewModel() {
        val rootDirectory: StateFlow<String> =
            appConfigRepository
                .getRootDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val imageDirectory: StateFlow<String> =
            appConfigRepository
                .getImageDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val voiceDirectory: StateFlow<String> =
            appConfigRepository
                .getVoiceDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val dateFormat: StateFlow<String> =
            appConfigRepository
                .getDateFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.DATE_FORMAT,
                )

        val timeFormat: StateFlow<String> =
            appConfigRepository
                .getTimeFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.TIME_FORMAT,
                )

        val themeMode: StateFlow<ThemeMode> =
            appConfigRepository
                .getThemeMode()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    ThemeMode.SYSTEM,
                )

        val hapticFeedbackEnabled: StateFlow<Boolean> =
            appConfigRepository
                .isHapticFeedbackEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED,
                )

        val showInputHints: StateFlow<Boolean> =
            appConfigRepository
                .isShowInputHintsEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.SHOW_INPUT_HINTS,
                )

        val doubleTapEditEnabled: StateFlow<Boolean> =
            appConfigRepository
                .isDoubleTapEditEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED,
                )

        val storageFilenameFormat: StateFlow<String> =
            appConfigRepository
                .getStorageFilenameFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.STORAGE_FILENAME_FORMAT,
                )

        val storageTimestampFormat: StateFlow<String> =
            appConfigRepository
                .getStorageTimestampFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT,
                )

        val checkUpdatesOnStartup: StateFlow<Boolean> =
            appConfigRepository
                .isCheckUpdatesOnStartupEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.CHECK_UPDATES_ON_STARTUP,
                )

        val shareCardStyle: StateFlow<ShareCardStyle> =
            appConfigRepository
                .getShareCardStyle()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    ShareCardStyle.CLEAN,
                )

        val shareCardShowTime: StateFlow<Boolean> =
            appConfigRepository
                .isShareCardShowTimeEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.SHARE_CARD_SHOW_TIME,
                )

        val shareCardShowBrand: StateFlow<Boolean> =
            appConfigRepository
                .isShareCardShowBrandEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.SHARE_CARD_SHOW_BRAND,
                )

        val lanShareE2eEnabled: StateFlow<Boolean> =
            shareServiceManager
                .lanShareE2eEnabled
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.LAN_SHARE_E2E_ENABLED,
                )

        val lanSharePairingConfigured: StateFlow<Boolean> =
            shareServiceManager
                .lanSharePairingConfigured
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    false,
                )

        val lanShareDeviceName: StateFlow<String> =
            shareServiceManager
                .lanShareDeviceName
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    "",
                )

        private val _pairingCodeError = MutableStateFlow<String?>(null)
        val pairingCodeError: StateFlow<String?> = _pairingCodeError.asStateFlow()
        private val _operationError = MutableStateFlow<String?>(null)
        val operationError: StateFlow<String?> = _operationError.asStateFlow()

        fun updateRootDirectory(path: String) {
            viewModelScope.launch { appConfigRepository.setRootDirectory(path) }
        }

        fun updateRootUri(uriString: String) {
            viewModelScope.launch { appConfigRepository.updateRootUri(uriString) }
        }

        fun updateImageDirectory(path: String) {
            viewModelScope.launch { appConfigRepository.setImageDirectory(path) }
        }

        fun updateImageUri(uriString: String) {
            viewModelScope.launch { appConfigRepository.updateImageUri(uriString) }
        }

        fun updateVoiceDirectory(path: String) {
            viewModelScope.launch { appConfigRepository.setVoiceDirectory(path) }
        }

        fun updateVoiceUri(uriString: String) {
            viewModelScope.launch { appConfigRepository.updateVoiceUri(uriString) }
        }

        fun updateDateFormat(format: String) {
            viewModelScope.launch { appConfigRepository.setDateFormat(format) }
        }

        fun updateTimeFormat(format: String) {
            viewModelScope.launch { appConfigRepository.setTimeFormat(format) }
        }

        fun updateThemeMode(mode: ThemeMode) {
            viewModelScope.launch { appConfigRepository.setThemeMode(mode) }
        }

        fun updateStorageFilenameFormat(format: String) {
            viewModelScope.launch { appConfigRepository.setStorageFilenameFormat(format) }
        }

        fun updateStorageTimestampFormat(format: String) {
            viewModelScope.launch { appConfigRepository.setStorageTimestampFormat(format) }
        }

        fun updateHapticFeedback(enabled: Boolean) {
            viewModelScope.launch { appConfigRepository.setHapticFeedbackEnabled(enabled) }
        }

        fun updateShowInputHints(enabled: Boolean) {
            viewModelScope.launch { appConfigRepository.setShowInputHints(enabled) }
        }

        fun updateDoubleTapEditEnabled(enabled: Boolean) {
            viewModelScope.launch { appConfigRepository.setDoubleTapEditEnabled(enabled) }
        }

        fun updateCheckUpdatesOnStartup(enabled: Boolean) {
            viewModelScope.launch { appConfigRepository.setCheckUpdatesOnStartup(enabled) }
        }

        fun updateShareCardStyle(style: ShareCardStyle) {
            viewModelScope.launch { appConfigRepository.setShareCardStyle(style) }
        }

        fun updateShareCardShowTime(enabled: Boolean) {
            viewModelScope.launch { appConfigRepository.setShareCardShowTime(enabled) }
        }

        fun updateShareCardShowBrand(enabled: Boolean) {
            viewModelScope.launch { appConfigRepository.setShareCardShowBrand(enabled) }
        }

        fun updateLanShareE2eEnabled(enabled: Boolean) {
            launchWithError("Failed to update secure share setting") {
                shareServiceManager.setLanShareE2eEnabled(enabled)
            }
        }

        fun updateLanSharePairingCode(pairingCode: String) {
            viewModelScope.launch {
                try {
                    shareServiceManager.setLanSharePairingCode(pairingCode)
                    _pairingCodeError.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    _pairingCodeError.value = INVALID_PAIRING_CODE_ERROR_MESSAGE
                }
            }
        }

        fun clearLanSharePairingCode() {
            viewModelScope.launch {
                try {
                    shareServiceManager.clearLanSharePairingCode()
                    _pairingCodeError.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to clear pairing code")
                }
            }
        }

        fun clearPairingCodeError() {
            _pairingCodeError.value = null
        }

        fun clearOperationError() {
            _operationError.value = null
        }

        fun updateLanShareDeviceName(deviceName: String) {
            launchWithError("Failed to update LAN share device name") {
                shareServiceManager.setLanShareDeviceName(deviceName)
            }
        }

        // Git Sync
        val gitSyncEnabled: StateFlow<Boolean> =
            gitSyncRepo
                .isGitSyncEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.GIT_SYNC_ENABLED,
                )

        val gitRemoteUrl: StateFlow<String> =
            gitSyncRepo
                .getRemoteUrl()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        private val _gitPatConfigured = MutableStateFlow(false)
        val gitPatConfigured: StateFlow<Boolean> = _gitPatConfigured.asStateFlow()

        val gitAuthorName: StateFlow<String> =
            gitSyncRepo
                .getAuthorName()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.GIT_AUTHOR_NAME,
                )

        val gitAuthorEmail: StateFlow<String> =
            gitSyncRepo
                .getAuthorEmail()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.GIT_AUTHOR_EMAIL,
                )

        val gitAutoSyncEnabled: StateFlow<Boolean> =
            gitSyncRepo
                .getAutoSyncEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.GIT_AUTO_SYNC_ENABLED,
                )

        val gitAutoSyncInterval: StateFlow<String> =
            gitSyncRepo
                .getAutoSyncInterval()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.GIT_AUTO_SYNC_INTERVAL,
                )

        val gitSyncOnRefreshEnabled: StateFlow<Boolean> =
            gitSyncRepo
                .getSyncOnRefreshEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceDefaults.GIT_SYNC_ON_REFRESH,
                )

        val gitLastSyncTime: StateFlow<Long> =
            gitSyncRepo
                .getLastSyncTime()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

        val gitSyncState: StateFlow<SyncEngineState> =
            gitSyncRepo
                .syncState()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    SyncEngineState.Idle,
                )

        init {
            launchWithError("Failed to read Git token state") {
                _gitPatConfigured.value = gitSyncRepo.getToken() != null
            }
        }

        fun updateGitSyncEnabled(enabled: Boolean) {
            launchWithError("Failed to update Git sync setting") {
                gitSyncRepo.setGitSyncEnabled(enabled)
                syncSchedulerRepository.rescheduleGitAutoSync()
            }
        }

        fun updateGitRemoteUrl(url: String) {
            launchWithError("Failed to update Git remote URL") {
                val normalized = url.removeSuffix("/")
                gitSyncRepo.setRemoteUrl(normalized)
            }
        }

        fun isValidGitRemoteUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return true // allow clearing
            return trimmed.startsWith("https://") && trimmed.count { it == '/' } >= 3
        }

        fun updateGitPat(token: String) {
            launchWithError("Failed to update Git token") {
                gitSyncRepo.setToken(token)
                _gitPatConfigured.value = token.isNotBlank()
            }
        }

        fun updateGitAuthorName(name: String) {
            launchWithError("Failed to update Git author name") {
                gitSyncRepo.setAuthorInfo(name, gitAuthorEmail.value)
            }
        }

        fun updateGitAuthorEmail(email: String) {
            launchWithError("Failed to update Git author email") {
                gitSyncRepo.setAuthorInfo(gitAuthorName.value, email)
            }
        }

        fun updateGitAutoSyncEnabled(enabled: Boolean) {
            launchWithError("Failed to update Git auto-sync setting") {
                gitSyncRepo.setAutoSyncEnabled(enabled)
                syncSchedulerRepository.rescheduleGitAutoSync()
            }
        }

        fun updateGitAutoSyncInterval(interval: String) {
            launchWithError("Failed to update Git auto-sync interval") {
                gitSyncRepo.setAutoSyncInterval(interval)
                syncSchedulerRepository.rescheduleGitAutoSync()
            }
        }

        fun updateGitSyncOnRefresh(enabled: Boolean) {
            launchWithError("Failed to update Git sync-on-refresh setting") {
                gitSyncRepo.setSyncOnRefreshEnabled(enabled)
            }
        }

        fun triggerGitSyncNow() {
            launchWithError("Failed to run Git sync") {
                syncAndRebuildUseCase(forceSync = true)
            }
        }

        fun resolveGitConflictUsingRemote() {
            viewModelScope.launch {
                try {
                    when (val resetResult = gitSyncRepo.resetLocalBranchToRemote()) {
                        is GitSyncResult.Error -> {
                            _operationError.value =
                                sanitizeUserFacingMessage(
                                    rawMessage = resetResult.message,
                                    fallbackMessage = "Failed to resolve conflict with remote history",
                                )
                        }

                        else -> {
                            // Refresh memo index after the local branch is reset to remote.
                            syncAndRebuildUseCase(forceSync = false)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to resolve conflict with remote history")
                }
            }
        }

        fun resolveGitConflictUsingLocal() {
            viewModelScope.launch {
                try {
                    when (val result = gitSyncRepo.forcePushLocalToRemote()) {
                        is GitSyncResult.Error -> {
                            _operationError.value =
                                sanitizeUserFacingMessage(
                                    rawMessage = result.message,
                                    fallbackMessage = "Failed to keep local changes during conflict resolution",
                                )
                        }

                        else -> {
                            syncAndRebuildUseCase(forceSync = false)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to keep local changes during conflict resolution")
                }
            }
        }

        // Connection test
        sealed interface ConnectionTestState {
            data object Idle : ConnectionTestState
            data object Testing : ConnectionTestState
            data class Success(val message: String) : ConnectionTestState
            data class Error(val message: String) : ConnectionTestState
        }

        private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
        val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

        fun testGitConnection() {
            viewModelScope.launch {
                try {
                    _connectionTestState.value = ConnectionTestState.Testing
                    val result = gitSyncRepo.testConnection()
                    _connectionTestState.value = when (result) {
                        is GitSyncResult.Success -> ConnectionTestState.Success(result.message)
                        is GitSyncResult.Error ->
                            ConnectionTestState.Error(
                                sanitizeUserFacingMessage(
                                    rawMessage = result.message,
                                    fallbackMessage = "Failed to test Git connection",
                                ),
                            )
                        else -> ConnectionTestState.Error("Unexpected result")
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    val message =
                        sanitizeUserFacingMessage(
                            rawMessage = throwable.message,
                            fallbackMessage = "Failed to test Git connection",
                        )
                    _connectionTestState.value = ConnectionTestState.Error(message)
                    reportOperationError(throwable, "Failed to test Git connection")
                }
            }
        }

        fun resetConnectionTestState() {
            _connectionTestState.value = ConnectionTestState.Idle
        }

        // Reset repository
        private val _resetInProgress = MutableStateFlow(false)
        val resetInProgress: StateFlow<Boolean> = _resetInProgress.asStateFlow()

        fun resetGitRepository() {
            viewModelScope.launch {
                _resetInProgress.value = true
                try {
                    when (val result = gitSyncRepo.resetRepository()) {
                        is GitSyncResult.Error -> {
                            _operationError.value =
                                sanitizeUserFacingMessage(
                                    rawMessage = result.message,
                                    fallbackMessage = "Failed to reset Git repository",
                                )
                        }

                        else -> Unit
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to reset Git repository")
                } finally {
                    _resetInProgress.value = false
                }
            }
        }

        private fun launchWithError(
            fallbackMessage: String,
            action: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    action()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, fallbackMessage)
                }
            }
        }

        private fun reportOperationError(
            throwable: Throwable,
            fallbackMessage: String,
        ) {
            if (throwable is CancellationException) throw throwable
            _operationError.value =
                sanitizeUserFacingMessage(
                    rawMessage = throwable.message,
                    fallbackMessage = fallbackMessage,
                )
        }

        private fun sanitizeUserFacingMessage(
            rawMessage: String?,
            fallbackMessage: String,
        ): String {
            val message = rawMessage?.trim().orEmpty()
            if (message.isBlank()) return fallbackMessage
            if (isGitSyncConflictMessage(message)) return message
            if (message.startsWith("Git sync requires direct path mode", ignoreCase = true)) return message
            if (looksTechnicalErrorMessage(message)) return fallbackMessage
            return message
        }

        private fun isGitSyncConflictMessage(message: String): Boolean =
            message.contains("rebase STOPPED", ignoreCase = true) ||
                message.contains("resolve conflicts manually", ignoreCase = true) ||
                (message.contains("rebase", ignoreCase = true) &&
                    message.contains("preserved", ignoreCase = true))

        private fun looksTechnicalErrorMessage(message: String): Boolean =
            message.length > 200 ||
                message.contains('\n') ||
                message.contains('\r') ||
                message.contains("exception", ignoreCase = true) ||
                message.contains("java.", ignoreCase = true) ||
                message.contains("kotlin.", ignoreCase = true) ||
                message.contains("stacktrace", ignoreCase = true) ||
                message.contains("\tat")

        private companion object {
            const val INVALID_PAIRING_CODE_ERROR_MESSAGE = "Pairing code must be 6-64 characters"
        }
    }
