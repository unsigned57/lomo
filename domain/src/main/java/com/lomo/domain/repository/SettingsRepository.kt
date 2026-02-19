package com.lomo.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user preferences and directory configuration writes.
 * Read-only directory access (getRootDirectory, getImageDirectory, etc.) remains
 * on [MemoRepository] since CRUD ViewModels need it too.
 */
interface SettingsRepository {
    // Directory writes
    suspend fun setRootDirectory(path: String)
    suspend fun setImageDirectory(path: String)
    suspend fun setVoiceDirectory(path: String)
    suspend fun updateRootUri(uri: String?)
    suspend fun updateImageUri(uri: String?)
    suspend fun updateVoiceUri(uri: String?)

    // Date / Time format
    fun getDateFormat(): Flow<String>
    suspend fun setDateFormat(format: String)
    fun getTimeFormat(): Flow<String>
    suspend fun setTimeFormat(format: String)

    // Theme
    fun getThemeMode(): Flow<String>
    suspend fun setThemeMode(mode: String)

    // Storage format
    fun getStorageFilenameFormat(): Flow<String>
    suspend fun setStorageFilenameFormat(format: String)
    fun getStorageTimestampFormat(): Flow<String>
    suspend fun setStorageTimestampFormat(format: String)

    // Feature flags
    fun isHapticFeedbackEnabled(): Flow<Boolean>
    suspend fun setHapticFeedbackEnabled(enabled: Boolean)
    fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean>
    suspend fun setCheckUpdatesOnStartup(enabled: Boolean)
    fun isShowInputHintsEnabled(): Flow<Boolean>
    suspend fun setShowInputHints(enabled: Boolean)

    // LAN share pairing
    fun isLanSharePairingConfigured(): Flow<Boolean>
    suspend fun setLanSharePairingCode(pairingCode: String)
    suspend fun clearLanSharePairingCode()
}
