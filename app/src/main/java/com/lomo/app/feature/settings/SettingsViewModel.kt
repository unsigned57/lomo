package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.data.share.ShareServiceManager
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.model.GitSyncState
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.SettingsRepository
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
        private val settings: SettingsRepository,
        private val shareServiceManager: ShareServiceManager,
        private val gitSyncRepo: GitSyncRepository,
    ) : ViewModel() {
        val rootDirectory: StateFlow<String> =
            settings
                .getRootDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val imageDirectory: StateFlow<String> =
            settings
                .getImageDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val voiceDirectory: StateFlow<String> =
            settings
                .getVoiceDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val dateFormat: StateFlow<String> =
            settings
                .getDateFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.DATE_FORMAT,
                )

        val timeFormat: StateFlow<String> =
            settings
                .getTimeFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.TIME_FORMAT,
                )

        val themeMode: StateFlow<String> =
            settings
                .getThemeMode()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.THEME_MODE,
                )

        val hapticFeedbackEnabled: StateFlow<Boolean> =
            settings
                .isHapticFeedbackEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED,
                )

        val showInputHints: StateFlow<Boolean> =
            settings
                .isShowInputHintsEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
                )

        val doubleTapEditEnabled: StateFlow<Boolean> =
            settings
                .isDoubleTapEditEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED,
                )

        val storageFilenameFormat: StateFlow<String> =
            settings
                .getStorageFilenameFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT,
                )

        val storageTimestampFormat: StateFlow<String> =
            settings
                .getStorageTimestampFormat()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT,
                )

        val checkUpdatesOnStartup: StateFlow<Boolean> =
            settings
                .isCheckUpdatesOnStartupEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP,
                )

        val shareCardStyle: StateFlow<String> =
            settings
                .getShareCardStyle()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHARE_CARD_STYLE,
                )

        val shareCardShowTime: StateFlow<Boolean> =
            settings
                .isShareCardShowTimeEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME,
                )

        val shareCardShowBrand: StateFlow<Boolean> =
            settings
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
            viewModelScope.launch { settings.setRootDirectory(path) }
        }

        fun updateRootUri(uriString: String) {
            viewModelScope.launch { settings.updateRootUri(uriString) }
        }

        fun updateImageDirectory(path: String) {
            viewModelScope.launch { settings.setImageDirectory(path) }
        }

        fun updateImageUri(uriString: String) {
            viewModelScope.launch { settings.updateImageUri(uriString) }
        }

        fun updateVoiceDirectory(path: String) {
            viewModelScope.launch { settings.setVoiceDirectory(path) }
        }

        fun updateVoiceUri(uriString: String) {
            viewModelScope.launch { settings.updateVoiceUri(uriString) }
        }

        fun updateDateFormat(format: String) {
            viewModelScope.launch { settings.setDateFormat(format) }
        }

        fun updateTimeFormat(format: String) {
            viewModelScope.launch { settings.setTimeFormat(format) }
        }

        fun updateThemeMode(mode: String) {
            viewModelScope.launch { settings.setThemeMode(mode) }
        }

        fun updateStorageFilenameFormat(format: String) {
            viewModelScope.launch { settings.setStorageFilenameFormat(format) }
        }

        fun updateStorageTimestampFormat(format: String) {
            viewModelScope.launch { settings.setStorageTimestampFormat(format) }
        }

        fun updateHapticFeedback(enabled: Boolean) {
            viewModelScope.launch { settings.setHapticFeedbackEnabled(enabled) }
        }

        fun updateShowInputHints(enabled: Boolean) {
            viewModelScope.launch { settings.setShowInputHints(enabled) }
        }

        fun updateDoubleTapEditEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setDoubleTapEditEnabled(enabled) }
        }

        fun updateCheckUpdatesOnStartup(enabled: Boolean) {
            viewModelScope.launch { settings.setCheckUpdatesOnStartup(enabled) }
        }

        fun updateShareCardStyle(style: String) {
            viewModelScope.launch { settings.setShareCardStyle(style) }
        }

        fun updateShareCardShowTime(enabled: Boolean) {
            viewModelScope.launch { settings.setShareCardShowTime(enabled) }
        }

        fun updateShareCardShowBrand(enabled: Boolean) {
            viewModelScope.launch { settings.setShareCardShowBrand(enabled) }
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
            viewModelScope.launch { gitSyncRepo.setGitSyncEnabled(enabled) }
        }

        fun updateGitRemoteUrl(url: String) {
            viewModelScope.launch { gitSyncRepo.setRemoteUrl(url) }
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
            viewModelScope.launch { gitSyncRepo.setAutoSyncEnabled(enabled) }
        }

        fun updateGitAutoSyncInterval(interval: String) {
            viewModelScope.launch { gitSyncRepo.setAutoSyncInterval(interval) }
        }

        fun triggerGitSyncNow() {
            viewModelScope.launch { gitSyncRepo.sync() }
        }
    }
