package com.lomo.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.util.StorageFilenameFormats
import com.lomo.domain.util.StorageTimestampFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Extension for DataStore creation with corruption handler
private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = PreferenceKeys.PREFS_NAME,
        produceMigrations = { emptyList() },
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

/**
 * DataStore-based preference manager for storing app settings. Replaces SharedPreferences with a
 * type-safe, coroutine-friendly API.
 */
@Singleton
class LomoDataStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val dataStore: DataStore<Preferences> = context.dataStore
        private companion object {
            private const val TAG = "LomoDataStore"
        }

        private fun <T> Flow<T>.catchOnlyIOException(
            flowName: String,
            fallback: T,
        ): Flow<T> =
            catch { throwable ->
                if (throwable is IOException) {
                    Timber.tag(TAG).e(throwable, "Error in %s flow", flowName)
                    emit(fallback)
                } else {
                    throw throwable
                }
            }

        // Keys
        private object Keys {
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
            val LAN_SHARE_PAIRING_KEY_HEX = stringPreferencesKey(PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX)
            val LAN_SHARE_E2E_ENABLED = booleanPreferencesKey(PreferenceKeys.LAN_SHARE_E2E_ENABLED)
            val LAN_SHARE_DEVICE_NAME = stringPreferencesKey(PreferenceKeys.LAN_SHARE_DEVICE_NAME)
            val SHARE_CARD_STYLE = stringPreferencesKey(PreferenceKeys.SHARE_CARD_STYLE)
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
        }

        // Storage Settings
        val rootUri: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.ROOT_URI] }
                .catchOnlyIOException("rootUri", null)

        val rootDirectory: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.ROOT_DIRECTORY] }
                .catchOnlyIOException("rootDirectory", null)

        /** Get root directory value once, for use in synchronous init. */
        suspend fun getRootDirectoryOnce(): String? =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.ROOT_URI] ?: prefs[Keys.ROOT_DIRECTORY]
                }.first()

        val imageUri: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.IMAGE_URI] }
                .catchOnlyIOException("imageUri", null)

        val imageDirectory: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.IMAGE_DIRECTORY] }
                .catchOnlyIOException("imageDirectory", null)

        val voiceUri: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.VOICE_URI] }
                .catchOnlyIOException("voiceUri", null)

        val voiceDirectory: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.VOICE_DIRECTORY] }
                .catchOnlyIOException("voiceDirectory", null)

        val storageFilenameFormat: Flow<String> =
            dataStore.data
                .map { prefs ->
                    StorageFilenameFormats.normalize(
                        prefs[Keys.STORAGE_FILENAME_FORMAT]
                            ?: PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT,
                    )
                }.catchOnlyIOException("storageFilenameFormat", PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT)

        val storageTimestampFormat: Flow<String> =
            dataStore.data
                .map { prefs ->
                    StorageTimestampFormats.normalize(
                        prefs[Keys.STORAGE_TIMESTAMP_FORMAT]
                            ?: PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT,
                    )
                }.catchOnlyIOException("storageTimestampFormat", PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT)

        // Display Settings
        val dateFormat: Flow<String> =
            dataStore.data
                .map { prefs -> prefs[Keys.DATE_FORMAT] ?: PreferenceKeys.Defaults.DATE_FORMAT }
                .catchOnlyIOException("dateFormat", PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: Flow<String> =
            dataStore.data
                .map { prefs -> prefs[Keys.TIME_FORMAT] ?: PreferenceKeys.Defaults.TIME_FORMAT }
                .catchOnlyIOException("timeFormat", PreferenceKeys.Defaults.TIME_FORMAT)

        val themeMode: Flow<String> =
            dataStore.data
                .map { prefs -> prefs[Keys.THEME_MODE] ?: PreferenceKeys.Defaults.THEME_MODE }
                .catchOnlyIOException("themeMode", PreferenceKeys.Defaults.THEME_MODE)

        // Interaction Settings
        val hapticFeedbackEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.HAPTIC_FEEDBACK_ENABLED]
                        ?: PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED
                }.catchOnlyIOException("hapticFeedbackEnabled", PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED)

        val checkUpdatesOnStartup: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.CHECK_UPDATES_ON_STARTUP]
                        ?: PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP
                }.catchOnlyIOException("checkUpdatesOnStartup", PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP)

        val showInputHints: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.SHOW_INPUT_HINTS]
                        ?: PreferenceKeys.Defaults.SHOW_INPUT_HINTS
                }.catchOnlyIOException("showInputHints", PreferenceKeys.Defaults.SHOW_INPUT_HINTS)

        val doubleTapEditEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.DOUBLE_TAP_EDIT_ENABLED]
                        ?: PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED
                }.catchOnlyIOException("doubleTapEditEnabled", PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED)

        val lanSharePairingKeyHex: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.LAN_SHARE_PAIRING_KEY_HEX] }
                .catchOnlyIOException("lanSharePairingKeyHex", null)

        val lanShareE2eEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.LAN_SHARE_E2E_ENABLED]
                        ?: PreferenceKeys.Defaults.LAN_SHARE_E2E_ENABLED
                }.catchOnlyIOException("lanShareE2eEnabled", PreferenceKeys.Defaults.LAN_SHARE_E2E_ENABLED)

        val lanShareDeviceName: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.LAN_SHARE_DEVICE_NAME] }
                .catchOnlyIOException("lanShareDeviceName", null)

        val shareCardStyle: Flow<String> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.SHARE_CARD_STYLE]
                        ?: PreferenceKeys.Defaults.SHARE_CARD_STYLE
                }.catchOnlyIOException("shareCardStyle", PreferenceKeys.Defaults.SHARE_CARD_STYLE)

        val shareCardShowTime: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.SHARE_CARD_SHOW_TIME]
                        ?: PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME
                }.catchOnlyIOException("shareCardShowTime", PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME)

        val shareCardShowBrand: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.SHARE_CARD_SHOW_BRAND]
                        ?: PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND
                }.catchOnlyIOException("shareCardShowBrand", PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND)

        // Update functions
        suspend fun updateRootUri(uri: String?) {
            dataStore.edit { prefs ->
                if (uri != null) {
                    prefs[Keys.ROOT_URI] = uri
                    prefs.remove(Keys.ROOT_DIRECTORY) // Clear legacy path
                } else {
                    prefs.remove(Keys.ROOT_URI)
                }
            }
        }

        suspend fun updateRootDirectory(path: String?) {
            dataStore.edit { prefs ->
                if (path != null) {
                    prefs[Keys.ROOT_DIRECTORY] = path
                } else {
                    prefs.remove(Keys.ROOT_DIRECTORY)
                }
            }
        }

        suspend fun updateImageUri(uri: String?) {
            dataStore.edit { prefs ->
                if (uri != null) {
                    prefs[Keys.IMAGE_URI] = uri
                    prefs.remove(Keys.IMAGE_DIRECTORY) // Clear legacy path
                } else {
                    prefs.remove(Keys.IMAGE_URI)
                }
            }
        }

        suspend fun updateImageDirectory(path: String?) {
            dataStore.edit { prefs ->
                if (path != null) {
                    prefs[Keys.IMAGE_DIRECTORY] = path
                } else {
                    prefs.remove(Keys.IMAGE_DIRECTORY)
                }
            }
        }

        suspend fun updateVoiceUri(uri: String?) {
            dataStore.edit { prefs ->
                if (uri != null) {
                    prefs[Keys.VOICE_URI] = uri
                    prefs.remove(Keys.VOICE_DIRECTORY)
                } else {
                    prefs.remove(Keys.VOICE_URI)
                }
            }
        }

        suspend fun updateVoiceDirectory(path: String?) {
            dataStore.edit { prefs ->
                if (path != null) {
                    prefs[Keys.VOICE_DIRECTORY] = path
                } else {
                    prefs.remove(Keys.VOICE_DIRECTORY)
                }
            }
        }

        suspend fun updateStorageFilenameFormat(format: String) {
            dataStore.edit { prefs ->
                prefs[Keys.STORAGE_FILENAME_FORMAT] = StorageFilenameFormats.normalize(format)
            }
        }

        suspend fun updateStorageTimestampFormat(format: String) {
            dataStore.edit { prefs ->
                prefs[Keys.STORAGE_TIMESTAMP_FORMAT] = StorageTimestampFormats.normalize(format)
            }
        }

        suspend fun updateDateFormat(format: String) {
            dataStore.edit { prefs -> prefs[Keys.DATE_FORMAT] = format }
        }

        suspend fun updateTimeFormat(format: String) {
            dataStore.edit { prefs -> prefs[Keys.TIME_FORMAT] = format }
        }

        suspend fun updateThemeMode(mode: String) {
            dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode }
        }

        suspend fun updateHapticFeedbackEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.HAPTIC_FEEDBACK_ENABLED] = enabled }
        }

        suspend fun updateCheckUpdatesOnStartup(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.CHECK_UPDATES_ON_STARTUP] = enabled }
        }

        suspend fun updateShowInputHints(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.SHOW_INPUT_HINTS] = enabled }
        }

        suspend fun updateDoubleTapEditEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.DOUBLE_TAP_EDIT_ENABLED] = enabled }
        }

        suspend fun updateLanSharePairingKeyHex(keyHex: String?) {
            dataStore.edit { prefs ->
                if (keyHex.isNullOrBlank()) {
                    prefs.remove(Keys.LAN_SHARE_PAIRING_KEY_HEX)
                } else {
                    prefs[Keys.LAN_SHARE_PAIRING_KEY_HEX] = keyHex
                }
            }
        }

        suspend fun updateLanShareE2eEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[Keys.LAN_SHARE_E2E_ENABLED] = enabled
            }
        }

        suspend fun updateLanShareDeviceName(name: String?) {
            dataStore.edit { prefs ->
                if (name.isNullOrBlank()) {
                    prefs.remove(Keys.LAN_SHARE_DEVICE_NAME)
                } else {
                    prefs[Keys.LAN_SHARE_DEVICE_NAME] = name
                }
            }
        }

        suspend fun updateShareCardStyle(style: String) {
            dataStore.edit { prefs ->
                prefs[Keys.SHARE_CARD_STYLE] = style
            }
        }

        suspend fun updateShareCardShowTime(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[Keys.SHARE_CARD_SHOW_TIME] = enabled
            }
        }

        suspend fun updateShareCardShowBrand(enabled: Boolean) {
            dataStore.edit { prefs ->
                prefs[Keys.SHARE_CARD_SHOW_BRAND] = enabled
            }
        }

        suspend fun getLastAppVersionOnce(): String? =
            dataStore.data
                .map { prefs -> prefs[Keys.LAST_APP_VERSION] }
                .first()

        suspend fun updateLastAppVersion(version: String?) {
            dataStore.edit { prefs ->
                if (version.isNullOrBlank()) {
                    prefs.remove(Keys.LAST_APP_VERSION)
                } else {
                    prefs[Keys.LAST_APP_VERSION] = version
                }
            }
        }

        // Git Sync Settings
        val gitSyncEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.GIT_SYNC_ENABLED]
                        ?: PreferenceKeys.Defaults.GIT_SYNC_ENABLED
                }.catchOnlyIOException("gitSyncEnabled", PreferenceKeys.Defaults.GIT_SYNC_ENABLED)

        val gitRemoteUrl: Flow<String?> =
            dataStore.data
                .map { prefs -> prefs[Keys.GIT_REMOTE_URL] }
                .catchOnlyIOException("gitRemoteUrl", null)

        val gitAuthorName: Flow<String> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.GIT_AUTHOR_NAME]
                        ?: PreferenceKeys.Defaults.GIT_AUTHOR_NAME
                }.catchOnlyIOException("gitAuthorName", PreferenceKeys.Defaults.GIT_AUTHOR_NAME)

        val gitAuthorEmail: Flow<String> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.GIT_AUTHOR_EMAIL]
                        ?: PreferenceKeys.Defaults.GIT_AUTHOR_EMAIL
                }.catchOnlyIOException("gitAuthorEmail", PreferenceKeys.Defaults.GIT_AUTHOR_EMAIL)

        val gitAutoSyncEnabled: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.GIT_AUTO_SYNC_ENABLED]
                        ?: PreferenceKeys.Defaults.GIT_AUTO_SYNC_ENABLED
                }.catchOnlyIOException("gitAutoSyncEnabled", PreferenceKeys.Defaults.GIT_AUTO_SYNC_ENABLED)

        val gitAutoSyncInterval: Flow<String> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.GIT_AUTO_SYNC_INTERVAL]
                        ?: PreferenceKeys.Defaults.GIT_AUTO_SYNC_INTERVAL
                }.catchOnlyIOException("gitAutoSyncInterval", PreferenceKeys.Defaults.GIT_AUTO_SYNC_INTERVAL)

        val gitLastSyncTime: Flow<Long> =
            dataStore.data
                .map { prefs -> prefs[Keys.GIT_LAST_SYNC_TIME] ?: 0L }
                .catchOnlyIOException("gitLastSyncTime", 0L)

        val gitSyncOnRefresh: Flow<Boolean> =
            dataStore.data
                .map { prefs ->
                    prefs[Keys.GIT_SYNC_ON_REFRESH]
                        ?: PreferenceKeys.Defaults.GIT_SYNC_ON_REFRESH
                }.catchOnlyIOException("gitSyncOnRefresh", PreferenceKeys.Defaults.GIT_SYNC_ON_REFRESH)

        suspend fun updateGitSyncEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.GIT_SYNC_ENABLED] = enabled }
        }

        suspend fun updateGitRemoteUrl(url: String?) {
            dataStore.edit { prefs ->
                if (url.isNullOrBlank()) {
                    prefs.remove(Keys.GIT_REMOTE_URL)
                } else {
                    prefs[Keys.GIT_REMOTE_URL] = url
                }
            }
        }

        suspend fun updateGitAuthorName(name: String) {
            dataStore.edit { prefs -> prefs[Keys.GIT_AUTHOR_NAME] = name }
        }

        suspend fun updateGitAuthorEmail(email: String) {
            dataStore.edit { prefs -> prefs[Keys.GIT_AUTHOR_EMAIL] = email }
        }

        suspend fun updateGitAutoSyncEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.GIT_AUTO_SYNC_ENABLED] = enabled }
        }

        suspend fun updateGitAutoSyncInterval(interval: String) {
            dataStore.edit { prefs -> prefs[Keys.GIT_AUTO_SYNC_INTERVAL] = interval }
        }

        suspend fun updateGitLastSyncTime(timestamp: Long) {
            dataStore.edit { prefs -> prefs[Keys.GIT_LAST_SYNC_TIME] = timestamp }
        }

        suspend fun updateGitSyncOnRefresh(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[Keys.GIT_SYNC_ON_REFRESH] = enabled }
        }
    }
