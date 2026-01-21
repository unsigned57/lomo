package com.lomo.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lomo.data.util.PreferenceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

// Extension for DataStore creation with corruption handler
private val Context.dataStore: DataStore<Preferences> by
        preferencesDataStore(
                name = PreferenceKeys.PREFS_NAME,
                produceMigrations = { emptyList() },
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
        )

/**
 * DataStore-based preference manager for storing app settings. Replaces SharedPreferences with a
 * type-safe, coroutine-friendly API.
 */
@Singleton
class LomoDataStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dataStore: DataStore<Preferences> = context.dataStore

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
    }

    // Storage Settings
    val rootUri: Flow<String?> =
            dataStore.data.map { prefs -> prefs[Keys.ROOT_URI] }.catch { e ->
                timber.log.Timber.e("LomoDataStore", "Error in rootUri flow", e)
                emit(null)
            }

    val rootDirectory: Flow<String?> =
            dataStore.data.map { prefs -> prefs[Keys.ROOT_DIRECTORY] }.catch { e ->
                timber.log.Timber.e("LomoDataStore", "Error in rootDirectory flow", e)
                emit(null)
            }

    /** Get root directory value once, for use in synchronous init. */
    suspend fun getRootDirectoryOnce(): String? {
        return dataStore.data.map { prefs -> 
            prefs[Keys.ROOT_URI] ?: prefs[Keys.ROOT_DIRECTORY] 
        }.first()
    }

    val imageUri: Flow<String?> =
            dataStore.data.map { prefs -> prefs[Keys.IMAGE_URI] }.catch { e ->
                timber.log.Timber.e("LomoDataStore", "Error in imageUri flow", e)
                emit(null)
            }

    val imageDirectory: Flow<String?> =
            dataStore.data.map { prefs -> prefs[Keys.IMAGE_DIRECTORY] }.catch { e ->
                timber.log.Timber.e("LomoDataStore", "Error in imageDirectory flow", e)
                emit(null)
            }

    val voiceUri: Flow<String?> =
            dataStore.data.map { prefs -> prefs[Keys.VOICE_URI] }.catch { e ->
                timber.log.Timber.e("LomoDataStore", "Error in voiceUri flow", e)
                emit(null)
            }

    val voiceDirectory: Flow<String?> =
            dataStore.data.map { prefs -> prefs[Keys.VOICE_DIRECTORY] }.catch { e ->
                timber.log.Timber.e("LomoDataStore", "Error in voiceDirectory flow", e)
                emit(null)
            }

    val storageFilenameFormat: Flow<String> =
            dataStore.data
                    .map { prefs ->
                        prefs[Keys.STORAGE_FILENAME_FORMAT]
                                ?: PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT
                    }
                    .catch { e ->
                        timber.log.Timber.e(
                                "LomoDataStore",
                                "Error in storageFilenameFormat flow",
                                e
                        )
                        emit(PreferenceKeys.Defaults.STORAGE_FILENAME_FORMAT)
                    }

    val storageTimestampFormat: Flow<String> =
            dataStore.data
                    .map { prefs ->
                        prefs[Keys.STORAGE_TIMESTAMP_FORMAT]
                                ?: PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT
                    }
                    .catch { e ->
                        timber.log.Timber.e(
                                "LomoDataStore",
                                "Error in storageTimestampFormat flow",
                                e
                        )
                        emit(PreferenceKeys.Defaults.STORAGE_TIMESTAMP_FORMAT)
                    }

    // Display Settings
    val dateFormat: Flow<String> =
            dataStore.data
                    .map { prefs -> prefs[Keys.DATE_FORMAT] ?: PreferenceKeys.Defaults.DATE_FORMAT }
                    .catch { e ->
                        timber.log.Timber.e("LomoDataStore", "Error in dateFormat flow", e)
                        emit(PreferenceKeys.Defaults.DATE_FORMAT)
                    }

    val timeFormat: Flow<String> =
            dataStore.data
                    .map { prefs -> prefs[Keys.TIME_FORMAT] ?: PreferenceKeys.Defaults.TIME_FORMAT }
                    .catch { e ->
                        timber.log.Timber.e("LomoDataStore", "Error in timeFormat flow", e)
                        emit(PreferenceKeys.Defaults.TIME_FORMAT)
                    }

    val themeMode: Flow<String> =
            dataStore.data
                    .map { prefs -> prefs[Keys.THEME_MODE] ?: PreferenceKeys.Defaults.THEME_MODE }
                    .catch { e ->
                        timber.log.Timber.e("LomoDataStore", "Error in themeMode flow", e)
                        emit(PreferenceKeys.Defaults.THEME_MODE)
                    }

    // Interaction Settings
    val hapticFeedbackEnabled: Flow<Boolean> =
            dataStore.data
                    .map { prefs ->
                        prefs[Keys.HAPTIC_FEEDBACK_ENABLED]
                                ?: PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED
                    }
                    .catch { e ->
                        timber.log.Timber.e(
                                "LomoDataStore",
                                "Error in hapticFeedbackEnabled flow",
                                e
                        )
                        emit(PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED)
                    }

    val checkUpdatesOnStartup: Flow<Boolean> =
            dataStore.data
                    .map { prefs ->
                        prefs[Keys.CHECK_UPDATES_ON_STARTUP]
                                ?: PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP
                    }
                    .catch { e ->
                        timber.log.Timber.e(
                                "LomoDataStore",
                                "Error in checkUpdatesOnStartup flow",
                                e
                        )
                        emit(PreferenceKeys.Defaults.CHECK_UPDATES_ON_STARTUP)
                    }

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
        dataStore.edit { prefs -> prefs[Keys.STORAGE_FILENAME_FORMAT] = format }
    }

    suspend fun updateStorageTimestampFormat(format: String) {
        dataStore.edit { prefs -> prefs[Keys.STORAGE_TIMESTAMP_FORMAT] = format }
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
}
