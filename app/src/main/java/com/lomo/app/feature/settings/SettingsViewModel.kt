package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.repository.LanShareService
import com.lomo.data.util.PreferenceKeys
import com.lomo.data.worker.GitSyncScheduler
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncState
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
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
        private val directorySettings: DirectorySettingsRepository,
        private val preferences: PreferencesRepository,
        private val shareServiceManager: LanShareService,
        private val gitSyncRepo: GitSyncRepository,
        private val gitSyncScheduler: GitSyncScheduler,
    ) : ViewModel() {
        val rootDirectory: StateFlow<String> =
            directorySettings
                .getRootDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val imageDirectory: StateFlow<String> =
            directorySettings
                .getImageDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val voiceDirectory: StateFlow<String> =
            directorySettings
                .getVoiceDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val dateFormat: StateFlow<String> =
            preferences
                .getDateFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.DATE_FORMAT,
                )

        val timeFormat: StateFlow<String> =
            preferences
                .getTimeFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.TIME_FORMAT,
                )

        val themeMode: StateFlow<ThemeMode> =
            preferences
                .getThemeMode()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    ThemeMode.SYSTEM,
                )

        val hapticFeedbackEnabled: StateFlow<Boolean> =
            preferences
                .isHapticFeedbackEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED,
                )

        val showInputHints: StateFlow<Boolean> =
            preferences
                .isShowInputHintsEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
                )

        val doubleTapEditEnabled: StateFlow<Boolean> =
            preferences
                .isDoubleTapEditEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED,
                )

        val storageFilenameFormat: StateFlow<String> =
            preferences
                .getStorageFilenameFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT,
                )

        val storageTimestampFormat: StateFlow<String> =
            preferences
                .getStorageTimestampFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT,
                )

        val checkUpdatesOnStartup: StateFlow<Boolean> =
            preferences
                .isCheckUpdatesOnStartupEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP,
                )

        val shareCardStyle: StateFlow<ShareCardStyle> =
            preferences
                .getShareCardStyle()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    ShareCardStyle.CLEAN,
                )

        val shareCardShowTime: StateFlow<Boolean> =
            preferences
                .isShareCardShowTimeEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME,
                )

        val shareCardShowBrand: StateFlow<Boolean> =
            preferences
                .isShareCardShowBrandEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND,
                )

        val lanShareE2eEnabled: StateFlow<Boolean> =
            shareServiceManager
                .lanShareE2eEnabled
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.LAN_SHARE_E2E_ENABLED,
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

        fun updateRootDirectory(path: String) {
            viewModelScope.launch { directorySettings.setRootDirectory(path) }
        }

        fun updateRootUri(uriString: String) {
            viewModelScope.launch { directorySettings.updateRootUri(uriString) }
        }

        fun updateImageDirectory(path: String) {
            viewModelScope.launch { directorySettings.setImageDirectory(path) }
        }

        fun updateImageUri(uriString: String) {
            viewModelScope.launch { directorySettings.updateImageUri(uriString) }
        }

        fun updateVoiceDirectory(path: String) {
            viewModelScope.launch { directorySettings.setVoiceDirectory(path) }
        }

        fun updateVoiceUri(uriString: String) {
            viewModelScope.launch { directorySettings.updateVoiceUri(uriString) }
        }

        fun updateDateFormat(format: String) {
            viewModelScope.launch { preferences.setDateFormat(format) }
        }

        fun updateTimeFormat(format: String) {
            viewModelScope.launch { preferences.setTimeFormat(format) }
        }

        fun updateThemeMode(mode: ThemeMode) {
            viewModelScope.launch { preferences.setThemeMode(mode) }
        }

        fun updateStorageFilenameFormat(format: String) {
            viewModelScope.launch { preferences.setStorageFilenameFormat(format) }
        }

        fun updateStorageTimestampFormat(format: String) {
            viewModelScope.launch { preferences.setStorageTimestampFormat(format) }
        }

        fun updateHapticFeedback(enabled: Boolean) {
            viewModelScope.launch { preferences.setHapticFeedbackEnabled(enabled) }
        }

        fun updateShowInputHints(enabled: Boolean) {
            viewModelScope.launch { preferences.setShowInputHints(enabled) }
        }

        fun updateDoubleTapEditEnabled(enabled: Boolean) {
            viewModelScope.launch { preferences.setDoubleTapEditEnabled(enabled) }
        }

        fun updateCheckUpdatesOnStartup(enabled: Boolean) {
            viewModelScope.launch { preferences.setCheckUpdatesOnStartup(enabled) }
        }

        fun updateShareCardStyle(style: ShareCardStyle) {
            viewModelScope.launch { preferences.setShareCardStyle(style) }
        }

        fun updateShareCardShowTime(enabled: Boolean) {
            viewModelScope.launch { preferences.setShareCardShowTime(enabled) }
        }

        fun updateShareCardShowBrand(enabled: Boolean) {
            viewModelScope.launch { preferences.setShareCardShowBrand(enabled) }
        }

        fun updateLanShareE2eEnabled(enabled: Boolean) {
            viewModelScope.launch {
                shareServiceManager.setLanShareE2eEnabled(enabled)
            }
        }

        fun updateLanSharePairingCode(pairingCode: String) {
            viewModelScope.launch {
                try {
                    shareServiceManager.setLanSharePairingCode(pairingCode)
                    _pairingCodeError.value = null
                } catch (e: Exception) {
                    _pairingCodeError.value = e.message ?: "Pairing code must be 6-64 characters"
                }
            }
        }

        fun clearLanSharePairingCode() {
            viewModelScope.launch {
                shareServiceManager.clearLanSharePairingCode()
                _pairingCodeError.value = null
            }
        }

        fun clearPairingCodeError() {
            _pairingCodeError.value = null
        }

        fun updateLanShareDeviceName(deviceName: String) {
            viewModelScope.launch {
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
                    PreferenceKeys.Defaults.GIT_SYNC_ENABLED,
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
                    PreferenceKeys.Defaults.GIT_AUTHOR_NAME,
                )

        val gitAuthorEmail: StateFlow<String> =
            gitSyncRepo
                .getAuthorEmail()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.GIT_AUTHOR_EMAIL,
                )

        val gitAutoSyncEnabled: StateFlow<Boolean> =
            gitSyncRepo
                .getAutoSyncEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.GIT_AUTO_SYNC_ENABLED,
                )

        val gitAutoSyncInterval: StateFlow<String> =
            gitSyncRepo
                .getAutoSyncInterval()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.GIT_AUTO_SYNC_INTERVAL,
                )

        val gitSyncOnRefreshEnabled: StateFlow<Boolean> =
            gitSyncRepo
                .getSyncOnRefreshEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.GIT_SYNC_ON_REFRESH,
                )

        val gitSyncOnFileChangeEnabled: StateFlow<Boolean> =
            gitSyncRepo
                .getSyncOnFileChangeEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.GIT_SYNC_ON_FILE_CHANGE,
                )

        val gitLastSyncTime: StateFlow<Long> =
            gitSyncRepo
                .getLastSyncTime()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

        val gitSyncState: StateFlow<GitSyncState> =
            gitSyncRepo
                .syncState()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    GitSyncState.Idle,
                )

        init {
            viewModelScope.launch {
                _gitPatConfigured.value = gitSyncRepo.getToken() != null
            }
        }

        fun updateGitSyncEnabled(enabled: Boolean) {
            viewModelScope.launch {
                gitSyncRepo.setGitSyncEnabled(enabled)
                gitSyncScheduler.reschedule()
            }
        }

        fun updateGitRemoteUrl(url: String) {
            viewModelScope.launch {
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
            viewModelScope.launch {
                gitSyncRepo.setToken(token)
                _gitPatConfigured.value = token.isNotBlank()
            }
        }

        fun updateGitAuthorName(name: String) {
            viewModelScope.launch { gitSyncRepo.setAuthorInfo(name, gitAuthorEmail.value) }
        }

        fun updateGitAuthorEmail(email: String) {
            viewModelScope.launch { gitSyncRepo.setAuthorInfo(gitAuthorName.value, email) }
        }

        fun updateGitAutoSyncEnabled(enabled: Boolean) {
            viewModelScope.launch {
                gitSyncRepo.setAutoSyncEnabled(enabled)
                gitSyncScheduler.reschedule()
            }
        }

        fun updateGitAutoSyncInterval(interval: String) {
            viewModelScope.launch {
                gitSyncRepo.setAutoSyncInterval(interval)
                gitSyncScheduler.reschedule()
            }
        }

        fun updateGitSyncOnRefresh(enabled: Boolean) {
            viewModelScope.launch { gitSyncRepo.setSyncOnRefreshEnabled(enabled) }
        }

        fun updateGitSyncOnFileChange(enabled: Boolean) {
            viewModelScope.launch { gitSyncRepo.setSyncOnFileChangeEnabled(enabled) }
        }

        fun triggerGitSyncNow() {
            viewModelScope.launch { gitSyncRepo.sync() }
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
                _connectionTestState.value = ConnectionTestState.Testing
                val result = gitSyncRepo.testConnection()
                _connectionTestState.value = when (result) {
                    is GitSyncResult.Success -> ConnectionTestState.Success(result.message)
                    is GitSyncResult.Error -> ConnectionTestState.Error(result.message)
                    else -> ConnectionTestState.Error("Unexpected result")
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
                gitSyncRepo.resetRepository()
                _resetInProgress.value = false
            }
        }
    }
