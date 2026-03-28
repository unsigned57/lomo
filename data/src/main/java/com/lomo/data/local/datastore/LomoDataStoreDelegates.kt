package com.lomo.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import kotlinx.coroutines.flow.Flow

internal class RootLocationStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoRootLocationStore {
    override val rootUri: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.ROOT_URI, "rootUri")

    override val rootDirectory: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.ROOT_DIRECTORY, "rootDirectory")

    override suspend fun updateRootUri(uri: String?) {
        dataStore.editPreferences {
            if (uri != null) {
                this[LomoDataStoreKeys.ROOT_URI] = uri
                remove(LomoDataStoreKeys.ROOT_DIRECTORY)
            } else {
                remove(LomoDataStoreKeys.ROOT_URI)
            }
        }
    }

    override suspend fun updateRootDirectory(path: String?) {
        dataStore.setOrRemove(LomoDataStoreKeys.ROOT_DIRECTORY, path)
    }

    override suspend fun getRootDirectoryOnce(): String? =
        dataStore.firstValue("getRootDirectoryOnce", null) { prefs ->
            prefs[LomoDataStoreKeys.ROOT_URI] ?: prefs[LomoDataStoreKeys.ROOT_DIRECTORY]
        }
}

internal class MediaLocationStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoMediaLocationStore {
    override val imageUri: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.IMAGE_URI, "imageUri")

    override val imageDirectory: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.IMAGE_DIRECTORY, "imageDirectory")

    override val voiceUri: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.VOICE_URI, "voiceUri")

    override val voiceDirectory: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.VOICE_DIRECTORY, "voiceDirectory")

    override suspend fun updateImageUri(uri: String?) {
        dataStore.editPreferences {
            if (uri != null) {
                this[LomoDataStoreKeys.IMAGE_URI] = uri
                remove(LomoDataStoreKeys.IMAGE_DIRECTORY)
            } else {
                remove(LomoDataStoreKeys.IMAGE_URI)
            }
        }
    }

    override suspend fun updateImageDirectory(path: String?) {
        dataStore.setOrRemove(LomoDataStoreKeys.IMAGE_DIRECTORY, path)
    }

    override suspend fun updateVoiceUri(uri: String?) {
        dataStore.editPreferences {
            if (uri != null) {
                this[LomoDataStoreKeys.VOICE_URI] = uri
                remove(LomoDataStoreKeys.VOICE_DIRECTORY)
            } else {
                remove(LomoDataStoreKeys.VOICE_URI)
            }
        }
    }

    override suspend fun updateVoiceDirectory(path: String?) {
        dataStore.setOrRemove(LomoDataStoreKeys.VOICE_DIRECTORY, path)
    }
}

internal class StorageFormatStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoStorageFormatStore {
    override val storageFilenameFormat: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.STORAGE_FILENAME_FORMAT,
            flowName = "storageFilenameFormat",
            default = PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT,
            normalize = StorageFilenameFormats::normalize,
        )

    override val storageTimestampFormat: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.STORAGE_TIMESTAMP_FORMAT,
            flowName = "storageTimestampFormat",
            default = PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT,
            normalize = StorageTimestampFormats::normalize,
        )

    override suspend fun updateStorageFilenameFormat(format: String) {
        dataStore.editPreferences {
            this[LomoDataStoreKeys.STORAGE_FILENAME_FORMAT] = StorageFilenameFormats.normalize(format)
        }
    }

    override suspend fun updateStorageTimestampFormat(format: String) {
        dataStore.editPreferences {
            this[LomoDataStoreKeys.STORAGE_TIMESTAMP_FORMAT] = StorageTimestampFormats.normalize(format)
        }
    }
}

internal class DisplayPreferencesStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoDisplayPreferencesStore {
    override val dateFormat: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.DATE_FORMAT,
            flowName = "dateFormat",
            default = PreferenceKeys.Defaults.DATE_FORMAT,
        )

    override val timeFormat: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.TIME_FORMAT,
            flowName = "timeFormat",
            default = PreferenceKeys.Defaults.TIME_FORMAT,
        )

    override val themeMode: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.THEME_MODE,
            flowName = "themeMode",
            default = PreferenceKeys.Defaults.THEME_MODE,
        )

    override suspend fun updateDateFormat(format: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.DATE_FORMAT] = format }
    }

    override suspend fun updateTimeFormat(format: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.TIME_FORMAT] = format }
    }

    override suspend fun updateThemeMode(mode: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.THEME_MODE] = mode }
    }
}

internal class InteractionPreferencesStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoInteractionPreferencesStore {
    override val hapticFeedbackEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.HAPTIC_FEEDBACK_ENABLED,
            flowName = "hapticFeedbackEnabled",
            default = PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED,
        )

    override val showInputHints: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.SHOW_INPUT_HINTS,
            flowName = "showInputHints",
            default = PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
        )

    override val doubleTapEditEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.DOUBLE_TAP_EDIT_ENABLED,
            flowName = "doubleTapEditEnabled",
            default = PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED,
        )

    override val freeTextCopyEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.FREE_TEXT_COPY_ENABLED,
            flowName = "freeTextCopyEnabled",
            default = PreferenceKeys.Defaults.FREE_TEXT_COPY_ENABLED,
        )

    override val memoActionAutoReorderEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.MEMO_ACTION_AUTO_REORDER_ENABLED,
            flowName = "memoActionAutoReorderEnabled",
            default = PreferenceKeys.Defaults.MEMO_ACTION_AUTO_REORDER_ENABLED,
        )

    override val memoActionOrder: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.MEMO_ACTION_ORDER,
            flowName = "memoActionOrder",
            default = PreferenceKeys.Defaults.MEMO_ACTION_ORDER,
        )

    override val quickSaveOnBackEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.QUICK_SAVE_ON_BACK_ENABLED,
            flowName = "quickSaveOnBackEnabled",
            default = PreferenceKeys.Defaults.QUICK_SAVE_ON_BACK_ENABLED,
        )

    override suspend fun updateHapticFeedbackEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.HAPTIC_FEEDBACK_ENABLED] = enabled }
    }

    override suspend fun updateShowInputHints(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.SHOW_INPUT_HINTS] = enabled }
    }

    override suspend fun updateDoubleTapEditEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.DOUBLE_TAP_EDIT_ENABLED] = enabled }
    }

    override suspend fun updateFreeTextCopyEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.FREE_TEXT_COPY_ENABLED] = enabled }
    }

    override suspend fun updateMemoActionAutoReorderEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.MEMO_ACTION_AUTO_REORDER_ENABLED] = enabled }
    }

    override suspend fun updateMemoActionOrder(order: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.MEMO_ACTION_ORDER] = order }
    }

    override suspend fun updateQuickSaveOnBackEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.QUICK_SAVE_ON_BACK_ENABLED] = enabled }
    }
}

internal class AppSecurityStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoAppSecurityStore {
    override val checkUpdatesOnStartup: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.CHECK_UPDATES_ON_STARTUP,
            flowName = "checkUpdatesOnStartup",
            default = PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP,
        )

    override val appLockEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.APP_LOCK_ENABLED,
            flowName = "appLockEnabled",
            default = PreferenceKeys.Defaults.APP_LOCK_ENABLED,
        )

    override suspend fun updateCheckUpdatesOnStartup(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.CHECK_UPDATES_ON_STARTUP] = enabled }
    }

    override suspend fun updateAppLockEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.APP_LOCK_ENABLED] = enabled }
    }
}

internal class LanSharePreferencesStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoLanSharePreferencesStore {
    override val lanSharePairingKeyHex: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.LAN_SHARE_PAIRING_KEY_HEX, "lanSharePairingKeyHex")

    override val lanShareE2eEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.LAN_SHARE_E2E_ENABLED,
            flowName = "lanShareE2eEnabled",
            default = PreferenceKeys.Defaults.LAN_SHARE_E2E_ENABLED,
        )

    override val lanShareDeviceName: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.LAN_SHARE_DEVICE_NAME, "lanShareDeviceName")

    override val shareCardShowTime: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.SHARE_CARD_SHOW_TIME,
            flowName = "shareCardShowTime",
            default = PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME,
        )

    override val shareCardShowBrand: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.SHARE_CARD_SHOW_BRAND,
            flowName = "shareCardShowBrand",
            default = PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND,
        )

    override suspend fun updateLanSharePairingKeyHex(keyHex: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.LAN_SHARE_PAIRING_KEY_HEX, keyHex)
    }

    override suspend fun updateLanShareE2eEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.LAN_SHARE_E2E_ENABLED] = enabled }
    }

    override suspend fun updateLanShareDeviceName(name: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.LAN_SHARE_DEVICE_NAME, name)
    }

    override suspend fun updateShareCardShowTime(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.SHARE_CARD_SHOW_TIME] = enabled }
    }

    override suspend fun updateShareCardShowBrand(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.SHARE_CARD_SHOW_BRAND] = enabled }
    }
}

internal class AppVersionStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoAppVersionStore {
    override suspend fun updateLastAppVersion(version: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.LAST_APP_VERSION, version)
    }

    override suspend fun getLastAppVersionOnce(): String? =
        dataStore.firstValue("getLastAppVersionOnce", null) { prefs ->
            prefs[LomoDataStoreKeys.LAST_APP_VERSION]
        }
}

internal class GitSyncBehaviorStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoGitSyncBehaviorStore {
    override val gitSyncEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.GIT_SYNC_ENABLED,
            flowName = "gitSyncEnabled",
            default = PreferenceKeys.Defaults.GIT_SYNC_ENABLED,
        )

    override val gitAutoSyncEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.GIT_AUTO_SYNC_ENABLED,
            flowName = "gitAutoSyncEnabled",
            default = PreferenceKeys.Defaults.GIT_AUTO_SYNC_ENABLED,
        )

    override val gitAutoSyncInterval: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.GIT_AUTO_SYNC_INTERVAL,
            flowName = "gitAutoSyncInterval",
            default = PreferenceKeys.Defaults.GIT_AUTO_SYNC_INTERVAL,
        )

    override val gitSyncOnRefresh: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.GIT_SYNC_ON_REFRESH,
            flowName = "gitSyncOnRefresh",
            default = PreferenceKeys.Defaults.GIT_SYNC_ON_REFRESH,
        )

    override val syncBackendType: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.SYNC_BACKEND_TYPE,
            flowName = "syncBackendType",
            default = PreferenceKeys.Defaults.SYNC_BACKEND_TYPE,
        )

    override suspend fun updateGitSyncEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_SYNC_ENABLED] = enabled }
    }

    override suspend fun updateGitAutoSyncEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_AUTO_SYNC_ENABLED] = enabled }
    }

    override suspend fun updateGitAutoSyncInterval(interval: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_AUTO_SYNC_INTERVAL] = interval }
    }

    override suspend fun updateGitSyncOnRefresh(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_SYNC_ON_REFRESH] = enabled }
    }

    override suspend fun updateSyncBackendType(type: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.SYNC_BACKEND_TYPE] = type }
    }
}

internal class GitIdentityStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoGitIdentityStore {
    override val gitRemoteUrl: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.GIT_REMOTE_URL, "gitRemoteUrl")

    override val gitAuthorName: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.GIT_AUTHOR_NAME,
            flowName = "gitAuthorName",
            default = PreferenceKeys.Defaults.GIT_AUTHOR_NAME,
        )

    override val gitAuthorEmail: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.GIT_AUTHOR_EMAIL,
            flowName = "gitAuthorEmail",
            default = PreferenceKeys.Defaults.GIT_AUTHOR_EMAIL,
        )

    override suspend fun updateGitRemoteUrl(url: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.GIT_REMOTE_URL, url)
    }

    override suspend fun updateGitAuthorName(name: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_AUTHOR_NAME] = name }
    }

    override suspend fun updateGitAuthorEmail(email: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_AUTHOR_EMAIL] = email }
    }
}

internal class GitSyncStatusStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoGitSyncStatusStore {
    override val gitLastSyncTime: Flow<Long> =
        dataStore.longFlow(
            key = LomoDataStoreKeys.GIT_LAST_SYNC_TIME,
            flowName = "gitLastSyncTime",
            default = 0L,
        )

    override suspend fun updateGitLastSyncTime(timestamp: Long) {
        dataStore.editPreferences { this[LomoDataStoreKeys.GIT_LAST_SYNC_TIME] = timestamp }
    }
}

internal class WebDavConnectionStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoWebDavConnectionStore {
    override val webDavSyncEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.WEBDAV_SYNC_ENABLED,
            flowName = "webDavSyncEnabled",
            default = PreferenceKeys.Defaults.WEBDAV_SYNC_ENABLED,
        )

    override val webDavProvider: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.WEBDAV_PROVIDER,
            flowName = "webDavProvider",
            default = PreferenceKeys.Defaults.WEBDAV_PROVIDER,
        )

    override val webDavBaseUrl: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.WEBDAV_BASE_URL, "webDavBaseUrl")

    override val webDavEndpointUrl: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.WEBDAV_ENDPOINT_URL, "webDavEndpointUrl")

    override val webDavUsername: Flow<String?> =
        dataStore.nullableStringFlow(LomoDataStoreKeys.WEBDAV_USERNAME, "webDavUsername")

    override suspend fun updateWebDavSyncEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.WEBDAV_SYNC_ENABLED] = enabled }
    }

    override suspend fun updateWebDavProvider(provider: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.WEBDAV_PROVIDER] = provider }
    }

    override suspend fun updateWebDavBaseUrl(url: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.WEBDAV_BASE_URL, url)
    }

    override suspend fun updateWebDavEndpointUrl(url: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.WEBDAV_ENDPOINT_URL, url)
    }

    override suspend fun updateWebDavUsername(username: String?) {
        dataStore.setOrRemoveIfBlank(LomoDataStoreKeys.WEBDAV_USERNAME, username)
    }
}

internal class WebDavScheduleStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoWebDavScheduleStore {
    override val webDavAutoSyncEnabled: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.WEBDAV_AUTO_SYNC_ENABLED,
            flowName = "webDavAutoSyncEnabled",
            default = PreferenceKeys.Defaults.WEBDAV_AUTO_SYNC_ENABLED,
        )

    override val webDavAutoSyncInterval: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.WEBDAV_AUTO_SYNC_INTERVAL,
            flowName = "webDavAutoSyncInterval",
            default = PreferenceKeys.Defaults.WEBDAV_AUTO_SYNC_INTERVAL,
        )

    override val webDavLastSyncTime: Flow<Long> =
        dataStore.longFlow(
            key = LomoDataStoreKeys.WEBDAV_LAST_SYNC_TIME,
            flowName = "webDavLastSyncTime",
            default = 0L,
        )

    override val webDavSyncOnRefresh: Flow<Boolean> =
        dataStore.booleanFlow(
            key = LomoDataStoreKeys.WEBDAV_SYNC_ON_REFRESH,
            flowName = "webDavSyncOnRefresh",
            default = PreferenceKeys.Defaults.WEBDAV_SYNC_ON_REFRESH,
        )

    override suspend fun updateWebDavAutoSyncEnabled(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.WEBDAV_AUTO_SYNC_ENABLED] = enabled }
    }

    override suspend fun updateWebDavAutoSyncInterval(interval: String) {
        dataStore.editPreferences { this[LomoDataStoreKeys.WEBDAV_AUTO_SYNC_INTERVAL] = interval }
    }

    override suspend fun updateWebDavLastSyncTime(timestamp: Long) {
        dataStore.editPreferences { this[LomoDataStoreKeys.WEBDAV_LAST_SYNC_TIME] = timestamp }
    }

    override suspend fun updateWebDavSyncOnRefresh(enabled: Boolean) {
        dataStore.editPreferences { this[LomoDataStoreKeys.WEBDAV_SYNC_ON_REFRESH] = enabled }
    }
}

internal class DraftStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : LomoDraftStore {
    override val draftText: Flow<String> =
        dataStore.stringFlow(
            key = LomoDataStoreKeys.DRAFT_TEXT,
            flowName = "draftText",
            default = "",
        )

    override suspend fun updateDraftText(text: String?) {
        dataStore.editPreferences {
            if (text.isNullOrEmpty()) {
                remove(LomoDataStoreKeys.DRAFT_TEXT)
            } else {
                this[LomoDataStoreKeys.DRAFT_TEXT] = text
            }
        }
    }
}
