package com.lomo.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lomo.data.util.PreferenceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val LOMO_DATA_STORE_TAG = "LomoDataStore"

private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = PreferenceKeys.PREFS_NAME,
        produceMigrations = { emptyList() },
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

interface LomoRootLocationStore {
    val rootUri: Flow<String?>
    val rootDirectory: Flow<String?>

    suspend fun updateRootUri(uri: String?)

    suspend fun updateRootDirectory(path: String?)

    suspend fun getRootDirectoryOnce(): String?
}

interface LomoMediaLocationStore {
    val imageUri: Flow<String?>
    val imageDirectory: Flow<String?>
    val voiceUri: Flow<String?>
    val voiceDirectory: Flow<String?>

    suspend fun updateImageUri(uri: String?)

    suspend fun updateImageDirectory(path: String?)

    suspend fun updateVoiceUri(uri: String?)

    suspend fun updateVoiceDirectory(path: String?)
}

interface LomoStorageFormatStore {
    val storageFilenameFormat: Flow<String>
    val storageTimestampFormat: Flow<String>

    suspend fun updateStorageFilenameFormat(format: String)

    suspend fun updateStorageTimestampFormat(format: String)
}

interface LomoDisplayPreferencesStore {
    val dateFormat: Flow<String>
    val timeFormat: Flow<String>
    val themeMode: Flow<String>

    suspend fun updateDateFormat(format: String)

    suspend fun updateTimeFormat(format: String)

    suspend fun updateThemeMode(mode: String)
}

interface LomoInteractionPreferencesStore {
    val hapticFeedbackEnabled: Flow<Boolean>
    val showInputHints: Flow<Boolean>
    val doubleTapEditEnabled: Flow<Boolean>
    val freeTextCopyEnabled: Flow<Boolean>
    val quickSaveOnBackEnabled: Flow<Boolean>

    suspend fun updateHapticFeedbackEnabled(enabled: Boolean)

    suspend fun updateShowInputHints(enabled: Boolean)

    suspend fun updateDoubleTapEditEnabled(enabled: Boolean)

    suspend fun updateFreeTextCopyEnabled(enabled: Boolean)

    suspend fun updateQuickSaveOnBackEnabled(enabled: Boolean)
}

interface LomoAppSecurityStore {
    val checkUpdatesOnStartup: Flow<Boolean>
    val appLockEnabled: Flow<Boolean>

    suspend fun updateCheckUpdatesOnStartup(enabled: Boolean)

    suspend fun updateAppLockEnabled(enabled: Boolean)
}

interface LomoLanSharePreferencesStore {
    val lanSharePairingKeyHex: Flow<String?>
    val lanShareE2eEnabled: Flow<Boolean>
    val lanShareDeviceName: Flow<String?>
    val shareCardShowTime: Flow<Boolean>
    val shareCardShowBrand: Flow<Boolean>

    suspend fun updateLanSharePairingKeyHex(keyHex: String?)

    suspend fun updateLanShareE2eEnabled(enabled: Boolean)

    suspend fun updateLanShareDeviceName(name: String?)

    suspend fun updateShareCardShowTime(enabled: Boolean)

    suspend fun updateShareCardShowBrand(enabled: Boolean)
}

interface LomoAppVersionStore {
    suspend fun updateLastAppVersion(version: String?)

    suspend fun getLastAppVersionOnce(): String?
}

interface LomoGitSyncBehaviorStore {
    val gitSyncEnabled: Flow<Boolean>
    val gitAutoSyncEnabled: Flow<Boolean>
    val gitAutoSyncInterval: Flow<String>
    val gitSyncOnRefresh: Flow<Boolean>
    val syncBackendType: Flow<String>

    suspend fun updateGitSyncEnabled(enabled: Boolean)

    suspend fun updateGitAutoSyncEnabled(enabled: Boolean)

    suspend fun updateGitAutoSyncInterval(interval: String)

    suspend fun updateGitSyncOnRefresh(enabled: Boolean)

    suspend fun updateSyncBackendType(type: String)
}

interface LomoGitIdentityStore {
    val gitRemoteUrl: Flow<String?>
    val gitAuthorName: Flow<String>
    val gitAuthorEmail: Flow<String>

    suspend fun updateGitRemoteUrl(url: String?)

    suspend fun updateGitAuthorName(name: String)

    suspend fun updateGitAuthorEmail(email: String)
}

interface LomoGitSyncStatusStore {
    val gitLastSyncTime: Flow<Long>

    suspend fun updateGitLastSyncTime(timestamp: Long)
}

interface LomoWebDavConnectionStore {
    val webDavSyncEnabled: Flow<Boolean>
    val webDavProvider: Flow<String>
    val webDavBaseUrl: Flow<String?>
    val webDavEndpointUrl: Flow<String?>
    val webDavUsername: Flow<String?>

    suspend fun updateWebDavSyncEnabled(enabled: Boolean)

    suspend fun updateWebDavProvider(provider: String)

    suspend fun updateWebDavBaseUrl(url: String?)

    suspend fun updateWebDavEndpointUrl(url: String?)

    suspend fun updateWebDavUsername(username: String?)
}

interface LomoWebDavScheduleStore {
    val webDavAutoSyncEnabled: Flow<Boolean>
    val webDavAutoSyncInterval: Flow<String>
    val webDavLastSyncTime: Flow<Long>
    val webDavSyncOnRefresh: Flow<Boolean>

    suspend fun updateWebDavAutoSyncEnabled(enabled: Boolean)

    suspend fun updateWebDavAutoSyncInterval(interval: String)

    suspend fun updateWebDavLastSyncTime(timestamp: Long)

    suspend fun updateWebDavSyncOnRefresh(enabled: Boolean)
}

interface LomoDraftStore {
    val draftText: Flow<String>

    suspend fun updateDraftText(text: String?)
}

/**
 * DataStore-backed settings shell. The API remains stable while implementation is split by concern.
 */
@Singleton
class LomoDataStore private constructor(
    dataStore: DataStore<Preferences>,
) : LomoRootLocationStore by RootLocationStoreImpl(dataStore),
    LomoMediaLocationStore by MediaLocationStoreImpl(dataStore),
    LomoStorageFormatStore by StorageFormatStoreImpl(dataStore),
    LomoDisplayPreferencesStore by DisplayPreferencesStoreImpl(dataStore),
    LomoInteractionPreferencesStore by InteractionPreferencesStoreImpl(dataStore),
    LomoAppSecurityStore by AppSecurityStoreImpl(dataStore),
    LomoLanSharePreferencesStore by LanSharePreferencesStoreImpl(dataStore),
    LomoAppVersionStore by AppVersionStoreImpl(dataStore),
    LomoGitSyncBehaviorStore by GitSyncBehaviorStoreImpl(dataStore),
    LomoGitIdentityStore by GitIdentityStoreImpl(dataStore),
    LomoGitSyncStatusStore by GitSyncStatusStoreImpl(dataStore),
    LomoWebDavConnectionStore by WebDavConnectionStoreImpl(dataStore),
    LomoWebDavScheduleStore by WebDavScheduleStoreImpl(dataStore),
    LomoDraftStore by DraftStoreImpl(dataStore) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.dataStore)
}

internal object LomoDataStoreKeys {
    val ROOT_URI = stringPreferencesKey(PreferenceKeys.ROOT_URI)
    val ROOT_DIRECTORY = stringPreferencesKey(PreferenceKeys.ROOT_DIRECTORY)
    val IMAGE_URI = stringPreferencesKey(PreferenceKeys.IMAGE_URI)
    val IMAGE_DIRECTORY = stringPreferencesKey(PreferenceKeys.IMAGE_DIRECTORY)
    val VOICE_URI = stringPreferencesKey("voice_uri")
    val VOICE_DIRECTORY = stringPreferencesKey("voice_directory")
    val STORAGE_FILENAME_FORMAT = stringPreferencesKey(PreferenceKeys.STORAGE_FILENAME_FORMAT)
    val STORAGE_TIMESTAMP_FORMAT = stringPreferencesKey(PreferenceKeys.STORAGE_TIMESTAMP_FORMAT)
    val DATE_FORMAT = stringPreferencesKey(PreferenceKeys.DATE_FORMAT)
    val TIME_FORMAT = stringPreferencesKey(PreferenceKeys.TIME_FORMAT)
    val THEME_MODE = stringPreferencesKey(PreferenceKeys.THEME_MODE)
    val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey(PreferenceKeys.HAPTIC_FEEDBACK_ENABLED)
    val CHECK_UPDATES_ON_STARTUP = booleanPreferencesKey(PreferenceKeys.CHECK_UPDATES_ON_STARTUP)
    val SHOW_INPUT_HINTS = booleanPreferencesKey(PreferenceKeys.SHOW_INPUT_HINTS)
    val DOUBLE_TAP_EDIT_ENABLED = booleanPreferencesKey(PreferenceKeys.DOUBLE_TAP_EDIT_ENABLED)
    val FREE_TEXT_COPY_ENABLED = booleanPreferencesKey(PreferenceKeys.FREE_TEXT_COPY_ENABLED)
    val QUICK_SAVE_ON_BACK_ENABLED = booleanPreferencesKey(PreferenceKeys.QUICK_SAVE_ON_BACK_ENABLED)
    val APP_LOCK_ENABLED = booleanPreferencesKey(PreferenceKeys.APP_LOCK_ENABLED)
    val LAN_SHARE_PAIRING_KEY_HEX = stringPreferencesKey(PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX)
    val LAN_SHARE_E2E_ENABLED = booleanPreferencesKey(PreferenceKeys.LAN_SHARE_E2E_ENABLED)
    val LAN_SHARE_DEVICE_NAME = stringPreferencesKey(PreferenceKeys.LAN_SHARE_DEVICE_NAME)
    val SHARE_CARD_SHOW_TIME = booleanPreferencesKey(PreferenceKeys.SHARE_CARD_SHOW_TIME)
    val SHARE_CARD_SHOW_BRAND = booleanPreferencesKey(PreferenceKeys.SHARE_CARD_SHOW_BRAND)
    val LAST_APP_VERSION = stringPreferencesKey(PreferenceKeys.LAST_APP_VERSION)
    val GIT_SYNC_ENABLED = booleanPreferencesKey(PreferenceKeys.GIT_SYNC_ENABLED)
    val GIT_REMOTE_URL = stringPreferencesKey(PreferenceKeys.GIT_REMOTE_URL)
    val GIT_AUTHOR_NAME = stringPreferencesKey(PreferenceKeys.GIT_AUTHOR_NAME)
    val GIT_AUTHOR_EMAIL = stringPreferencesKey(PreferenceKeys.GIT_AUTHOR_EMAIL)
    val GIT_AUTO_SYNC_ENABLED = booleanPreferencesKey(PreferenceKeys.GIT_AUTO_SYNC_ENABLED)
    val GIT_AUTO_SYNC_INTERVAL = stringPreferencesKey(PreferenceKeys.GIT_AUTO_SYNC_INTERVAL)
    val GIT_LAST_SYNC_TIME = longPreferencesKey(PreferenceKeys.GIT_LAST_SYNC_TIME)
    val GIT_SYNC_ON_REFRESH = booleanPreferencesKey(PreferenceKeys.GIT_SYNC_ON_REFRESH)
    val SYNC_BACKEND_TYPE = stringPreferencesKey(PreferenceKeys.SYNC_BACKEND_TYPE)
    val WEBDAV_SYNC_ENABLED = booleanPreferencesKey(PreferenceKeys.WEBDAV_SYNC_ENABLED)
    val WEBDAV_PROVIDER = stringPreferencesKey(PreferenceKeys.WEBDAV_PROVIDER)
    val WEBDAV_BASE_URL = stringPreferencesKey(PreferenceKeys.WEBDAV_BASE_URL)
    val WEBDAV_ENDPOINT_URL = stringPreferencesKey(PreferenceKeys.WEBDAV_ENDPOINT_URL)
    val WEBDAV_USERNAME = stringPreferencesKey(PreferenceKeys.WEBDAV_USERNAME)
    val WEBDAV_AUTO_SYNC_ENABLED = booleanPreferencesKey(PreferenceKeys.WEBDAV_AUTO_SYNC_ENABLED)
    val WEBDAV_AUTO_SYNC_INTERVAL = stringPreferencesKey(PreferenceKeys.WEBDAV_AUTO_SYNC_INTERVAL)
    val WEBDAV_LAST_SYNC_TIME = longPreferencesKey(PreferenceKeys.WEBDAV_LAST_SYNC_TIME)
    val WEBDAV_SYNC_ON_REFRESH = booleanPreferencesKey(PreferenceKeys.WEBDAV_SYNC_ON_REFRESH)
    val DRAFT_TEXT = stringPreferencesKey(PreferenceKeys.DRAFT_TEXT)
}

internal fun DataStore<Preferences>.nullableStringFlow(
    key: Preferences.Key<String>,
    flowName: String,
): Flow<String?> =
    data
        .map { prefs -> prefs[key] }
        .catchOnlyIOException(flowName, null)

internal fun DataStore<Preferences>.stringFlow(
    key: Preferences.Key<String>,
    flowName: String,
    default: String,
    normalize: (String) -> String = { it },
): Flow<String> {
    val fallback = normalize(default)
    return data
        .map { prefs -> normalize(prefs[key] ?: default) }
        .catchOnlyIOException(flowName, fallback)
}

internal fun DataStore<Preferences>.booleanFlow(
    key: Preferences.Key<Boolean>,
    flowName: String,
    default: Boolean,
): Flow<Boolean> =
    data
        .map { prefs -> prefs[key] ?: default }
        .catchOnlyIOException(flowName, default)

internal fun DataStore<Preferences>.longFlow(
    key: Preferences.Key<Long>,
    flowName: String,
    default: Long,
): Flow<Long> =
    data
        .map { prefs -> prefs[key] ?: default }
        .catchOnlyIOException(flowName, default)

internal suspend fun <T> DataStore<Preferences>.firstValue(
    flowName: String,
    fallback: T,
    reader: (Preferences) -> T,
): T =
    data
        .map(reader)
        .catchOnlyIOException(flowName, fallback)
        .first()

internal suspend fun DataStore<Preferences>.editPreferences(
    block: MutablePreferences.() -> Unit,
) {
    edit { prefs -> prefs.block() }
}

internal suspend fun DataStore<Preferences>.setOrRemove(
    key: Preferences.Key<String>,
    value: String?,
) {
    editPreferences {
        if (value != null) {
            this[key] = value
        } else {
            remove(key)
        }
    }
}

internal suspend fun DataStore<Preferences>.setOrRemoveIfBlank(
    key: Preferences.Key<String>,
    value: String?,
) {
    editPreferences {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }
}

internal fun <T> Flow<T>.catchOnlyIOException(
    flowName: String,
    fallback: T,
): Flow<T> =
    catch { throwable ->
        if (throwable is IOException) {
            Timber.tag(LOMO_DATA_STORE_TAG).e(throwable, "Error in %s flow", flowName)
            emit(fallback)
        } else {
            throw throwable
        }
    }
