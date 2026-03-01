package com.lomo.app.feature.settings

import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.model.PreferenceDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsAppConfigCoordinator(
    private val appConfigRepository: AppConfigRepository,
    private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    scope: CoroutineScope,
) {
    val rootDirectory: StateFlow<String> =
        appConfigRepository
            .observeRootDisplayName()
            .map { it ?: "" }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), "")

    val imageDirectory: StateFlow<String> =
        appConfigRepository
            .observeImageDisplayName()
            .map { it ?: "" }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), "")

    val voiceDirectory: StateFlow<String> =
        appConfigRepository
            .observeVoiceDisplayName()
            .map { it ?: "" }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), "")

    val dateFormat: StateFlow<String> =
        appConfigRepository
            .getDateFormat()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.DATE_FORMAT)

    val timeFormat: StateFlow<String> =
        appConfigRepository
            .getTimeFormat()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.TIME_FORMAT)

    val themeMode: StateFlow<ThemeMode> =
        appConfigRepository
            .getThemeMode()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val hapticFeedbackEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isHapticFeedbackEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)

    val showInputHints: StateFlow<Boolean> =
        appConfigRepository
            .isShowInputHintsEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.SHOW_INPUT_HINTS)

    val doubleTapEditEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isDoubleTapEditEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)

    val appLockEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isAppLockEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.APP_LOCK_ENABLED)

    val storageFilenameFormat: StateFlow<String> =
        appConfigRepository
            .getStorageFilenameFormat()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.STORAGE_FILENAME_FORMAT)

    val storageTimestampFormat: StateFlow<String> =
        appConfigRepository
            .getStorageTimestampFormat()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)

    val checkUpdatesOnStartup: StateFlow<Boolean> =
        appConfigRepository
            .isCheckUpdatesOnStartupEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)

    val shareCardStyle: StateFlow<ShareCardStyle> =
        appConfigRepository
            .getShareCardStyle()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), ShareCardStyle.CLEAN)

    val shareCardShowTime: StateFlow<Boolean> =
        appConfigRepository
            .isShareCardShowTimeEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.SHARE_CARD_SHOW_TIME)

    val shareCardShowBrand: StateFlow<Boolean> =
        appConfigRepository
            .isShareCardShowBrandEnabled()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.SHARE_CARD_SHOW_BRAND)

    suspend fun updateRootDirectory(path: String) {
        switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
    }

    suspend fun updateRootUri(uriString: String) {
        switchRootStorageUseCase.updateRootLocation(StorageLocation(uriString))
    }

    suspend fun updateImageDirectory(path: String) {
        appConfigRepository.applyLocation(
            StorageAreaUpdate(
                area = StorageArea.IMAGE,
                location = StorageLocation(path),
            ),
        )
    }

    suspend fun updateImageUri(uriString: String) {
        appConfigRepository.applyLocation(
            StorageAreaUpdate(
                area = StorageArea.IMAGE,
                location = StorageLocation(uriString),
            ),
        )
    }

    suspend fun updateVoiceDirectory(path: String) {
        appConfigRepository.applyLocation(
            StorageAreaUpdate(
                area = StorageArea.VOICE,
                location = StorageLocation(path),
            ),
        )
    }

    suspend fun updateVoiceUri(uriString: String) {
        appConfigRepository.applyLocation(
            StorageAreaUpdate(
                area = StorageArea.VOICE,
                location = StorageLocation(uriString),
            ),
        )
    }

    suspend fun updateDateFormat(format: String) {
        appConfigRepository.setDateFormat(format)
    }

    suspend fun updateTimeFormat(format: String) {
        appConfigRepository.setTimeFormat(format)
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        appConfigRepository.setThemeMode(mode)
    }

    suspend fun updateStorageFilenameFormat(format: String) {
        appConfigRepository.setStorageFilenameFormat(format)
    }

    suspend fun updateStorageTimestampFormat(format: String) {
        appConfigRepository.setStorageTimestampFormat(format)
    }

    suspend fun updateHapticFeedback(enabled: Boolean) {
        appConfigRepository.setHapticFeedbackEnabled(enabled)
    }

    suspend fun updateShowInputHints(enabled: Boolean) {
        appConfigRepository.setShowInputHints(enabled)
    }

    suspend fun updateDoubleTapEditEnabled(enabled: Boolean) {
        appConfigRepository.setDoubleTapEditEnabled(enabled)
    }

    suspend fun updateAppLockEnabled(enabled: Boolean) {
        appConfigRepository.setAppLockEnabled(enabled)
    }

    suspend fun updateCheckUpdatesOnStartup(enabled: Boolean) {
        appConfigRepository.setCheckUpdatesOnStartup(enabled)
    }

    suspend fun updateShareCardStyle(style: ShareCardStyle) {
        appConfigRepository.setShareCardStyle(style)
    }

    suspend fun updateShareCardShowTime(enabled: Boolean) {
        appConfigRepository.setShareCardShowTime(enabled)
    }

    suspend fun updateShareCardShowBrand(enabled: Boolean) {
        appConfigRepository.setShareCardShowBrand(enabled)
    }
}
