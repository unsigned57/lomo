package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsAppConfigCoordinator(
    private val appConfigRepository: AppConfigRepository,
    private val switchRootStorageUseCase: SwitchRootStorageUseCase,
    scope: CoroutineScope,
    private val memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository =
        NoOpMemoSnapshotPreferencesRepository,
    private val memoVersionRepository: MemoVersionRepository? = null,
) {
    val rootDirectory: StateFlow<DirectoryDisplayState> =
        appConfigRepository
            .observeRootDisplayName()
            .asDirectoryDisplayState(scope)

    val imageDirectory: StateFlow<DirectoryDisplayState> =
        appConfigRepository
            .observeImageDisplayName()
            .asDirectoryDisplayState(scope)

    val voiceDirectory: StateFlow<DirectoryDisplayState> =
        appConfigRepository
            .observeVoiceDisplayName()
            .asDirectoryDisplayState(scope)

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

    val memoActionAutoReorderEnabled: StateFlow<Boolean> =
        appConfigRepository
            .isMemoActionAutoReorderEnabled()
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED,
            )

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

    val memoSnapshotsEnabled: StateFlow<Boolean> =
        memoSnapshotPreferencesRepository
            .isMemoSnapshotsEnabled()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED)

    val memoSnapshotMaxCount: StateFlow<Int> =
        memoSnapshotPreferencesRepository
            .getMemoSnapshotMaxCount()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT)

    val memoSnapshotMaxAgeDays: StateFlow<Int> =
        memoSnapshotPreferencesRepository
            .getMemoSnapshotMaxAgeDays()
            .stateIn(scope, settingsWhileSubscribed(), PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS)

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

    val updateMemoActionAutoReorderEnabled: suspend (Boolean) -> Unit =
        { enabled -> appConfigRepository.setMemoActionAutoReorderEnabled(enabled) }

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

    val updateMemoSnapshotsEnabled: suspend (Boolean) -> Unit =
        { enabled ->
            memoSnapshotPreferencesRepository.setMemoSnapshotsEnabled(enabled)
            if (!enabled) {
                memoVersionRepository?.clearAllMemoSnapshots()
            }
        }

    val updateMemoSnapshotMaxCount: suspend (Int) -> Unit =
        { count -> memoSnapshotPreferencesRepository.setMemoSnapshotMaxCount(count) }

    val updateMemoSnapshotMaxAgeDays: suspend (Int) -> Unit =
        { days -> memoSnapshotPreferencesRepository.setMemoSnapshotMaxAgeDays(days) }
}

private object NoOpMemoSnapshotPreferencesRepository : MemoSnapshotPreferencesRepository {
    override fun isMemoSnapshotsEnabled() = flowOf(PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED)

    override suspend fun setMemoSnapshotsEnabled(enabled: Boolean) = Unit

    override fun getMemoSnapshotMaxCount() = flowOf(PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT)

    override suspend fun setMemoSnapshotMaxCount(count: Int) = Unit

    override fun getMemoSnapshotMaxAgeDays() = flowOf(PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS)

    override suspend fun setMemoSnapshotMaxAgeDays(days: Int) = Unit
}

private fun Flow<String?>.asDirectoryDisplayState(scope: CoroutineScope): StateFlow<DirectoryDisplayState> =
    map(DirectoryDisplayState::Resolved)
        .stateIn(scope, settingsWhileSubscribed(), DirectoryDisplayState.Loading)
