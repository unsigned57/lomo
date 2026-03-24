package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import kotlinx.coroutines.CoroutineScope
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
            .stateIn(scope, settingsWhileSubscribed(), "")

    val imageDirectory: StateFlow<String> =
        appConfigRepository
            .observeImageDisplayName()
            .map { it ?: "" }
            .stateIn(scope, settingsWhileSubscribed(), "")

    val voiceDirectory: StateFlow<String> =
        appConfigRepository
            .observeVoiceDisplayName()
            .map { it ?: "" }
            .stateIn(scope, settingsWhileSubscribed(), "")

    val dateFormat: StateFlow<String> =
        appConfigRepository
            .getDateFormat()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.DATE_FORMAT)

    val timeFormat: StateFlow<String> =
        appConfigRepository
            .getTimeFormat()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.TIME_FORMAT)

    val themeMode: StateFlow<ThemeMode> =
        appConfigRepository
            .getThemeMode()
            .stateIn(scope, settingsWhileSubscribed(), ThemeMode.SYSTEM)

    val hapticFeedbackEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isHapticFeedbackEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)

    val showInputHints: StateFlow<Boolean> =
        appConfigRepository
            .isShowInputHintsEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.SHOW_INPUT_HINTS)

    val doubleTapEditEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isDoubleTapEditEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)

    val freeTextCopyEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isFreeTextCopyEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.FREE_TEXT_COPY_ENABLED)

    val quickSaveOnBackEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isQuickSaveOnBackEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)

    val appLockEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isAppLockEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.APP_LOCK_ENABLED)

    val storageFilenameFormat: StateFlow<String> =
        appConfigRepository
            .getStorageFilenameFormat()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.STORAGE_FILENAME_FORMAT)

    val storageTimestampFormat: StateFlow<String> =
        appConfigRepository
            .getStorageTimestampFormat()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)

    val checkUpdatesOnStartup: StateFlow<Boolean> =
        appConfigRepository
            .isCheckUpdatesOnStartupEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)

    val shareCardShowTime: StateFlow<Boolean> =
        appConfigRepository
            .isShareCardShowTimeEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.SHARE_CARD_SHOW_TIME)

    val shareCardShowBrand: StateFlow<Boolean> =
        appConfigRepository
            .isShareCardShowBrandEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.SHARE_CARD_SHOW_BRAND)

    val updateRootDirectory: suspend (String) -> Unit =
        { path ->
            switchRootStorageUseCase.updateRootLocation(StorageLocation(path))
        }

    val updateRootUri: suspend (String) -> Unit =
        { uriString ->
            switchRootStorageUseCase.updateRootLocation(StorageLocation(uriString))
        }

    val updateImageDirectory: suspend (String) -> Unit =
        { path ->
            appConfigRepository.applyLocation(
                StorageAreaUpdate(
                    area = StorageArea.IMAGE,
                    location = StorageLocation(path),
                ),
            )
        }

    val updateImageUri: suspend (String) -> Unit =
        { uriString ->
            appConfigRepository.applyLocation(
                StorageAreaUpdate(
                    area = StorageArea.IMAGE,
                    location = StorageLocation(uriString),
                ),
            )
        }

    val updateVoiceDirectory: suspend (String) -> Unit =
        { path ->
            appConfigRepository.applyLocation(
                StorageAreaUpdate(
                    area = StorageArea.VOICE,
                    location = StorageLocation(path),
                ),
            )
        }

    val updateVoiceUri: suspend (String) -> Unit =
        { uriString ->
            appConfigRepository.applyLocation(
                StorageAreaUpdate(
                    area = StorageArea.VOICE,
                    location = StorageLocation(uriString),
                ),
            )
        }

    val updateDateFormat: suspend (String) -> Unit =
        { format -> appConfigRepository.setDateFormat(format) }

    val updateTimeFormat: suspend (String) -> Unit =
        { format -> appConfigRepository.setTimeFormat(format) }

    val updateThemeMode: suspend (ThemeMode) -> Unit =
        { mode -> appConfigRepository.setThemeMode(mode) }

    val updateStorageFilenameFormat: suspend (String) -> Unit =
        { format -> appConfigRepository.setStorageFilenameFormat(format) }

    val updateStorageTimestampFormat: suspend (String) -> Unit =
        { format -> appConfigRepository.setStorageTimestampFormat(format) }

    val updateHapticFeedback: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setHapticFeedbackEnabled(enabled) }

    val updateShowInputHints: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setShowInputHintsEnabled(enabled) }

    val updateDoubleTapEditEnabled: suspend (Boolean) -> Unit =
        { enabled ->
            appConfigRepository.setDoubleTapEditEnabled(enabled)
            if (enabled) {
                appConfigRepository.setFreeTextCopyEnabled(false)
            }
        }

    val updateFreeTextCopyEnabled: suspend (Boolean) -> Unit =
        { enabled ->
            appConfigRepository.setFreeTextCopyEnabled(enabled)
            if (enabled) {
                appConfigRepository.setDoubleTapEditEnabled(false)
            }
        }

    val updateQuickSaveOnBackEnabled: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setQuickSaveOnBackEnabled(enabled) }

    val updateAppLockEnabled: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setAppLockEnabled(enabled) }

    val updateCheckUpdatesOnStartup: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setCheckUpdatesOnStartup(enabled) }

    val updateShareCardShowTime: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setShareCardShowTime(enabled) }

    val updateShareCardShowBrand: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setShareCardShowBrand(enabled) }
}
