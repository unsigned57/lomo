package com.lomo.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        val rootDirectory: StateFlow<String> =
            repository
                .getRootDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val imageDirectory: StateFlow<String> =
            repository
                .getImageDisplayName()
                .map { it ?: "" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val voiceDirectory: StateFlow<String> =
            repository
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
    }
