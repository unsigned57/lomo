package com.lomo.domain.repository

import com.lomo.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface DateTimePreferencesRepository {
    fun getDateFormat(): Flow<String>

    suspend fun setDateFormat(format: String)

    fun getTimeFormat(): Flow<String>

    suspend fun setTimeFormat(format: String)

    fun getThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)
}

interface StoragePreferencesRepository {
    fun getStorageFilenameFormat(): Flow<String>

    suspend fun setStorageFilenameFormat(format: String)

    fun getStorageTimestampFormat(): Flow<String>

    suspend fun setStorageTimestampFormat(format: String)
}

interface InteractionPreferencesRepository {
    fun isHapticFeedbackEnabled(): Flow<Boolean>

    suspend fun setHapticFeedbackEnabled(enabled: Boolean)

    fun isShowInputHintsEnabled(): Flow<Boolean>

    suspend fun setShowInputHintsEnabled(enabled: Boolean)

    fun isDoubleTapEditEnabled(): Flow<Boolean>

    suspend fun setDoubleTapEditEnabled(enabled: Boolean)

    fun isFreeTextCopyEnabled(): Flow<Boolean>

    suspend fun setFreeTextCopyEnabled(enabled: Boolean)

    fun isQuickSaveOnBackEnabled(): Flow<Boolean>

    suspend fun setQuickSaveOnBackEnabled(enabled: Boolean)
}

interface MemoActionPreferencesRepository {
    fun isMemoActionAutoReorderEnabled(): Flow<Boolean>

    suspend fun setMemoActionAutoReorderEnabled(enabled: Boolean)

    fun getMemoActionOrder(): Flow<List<String>>

    suspend fun updateMemoActionOrder(actionOrder: List<String>)
}

interface SecurityPreferencesRepository {
    fun isAppLockEnabled(): Flow<Boolean>

    suspend fun setAppLockEnabled(enabled: Boolean)

    fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean>

    suspend fun setCheckUpdatesOnStartup(enabled: Boolean)
}

interface ShareCardPreferencesRepository {
    fun isShareCardShowTimeEnabled(): Flow<Boolean>

    suspend fun setShareCardShowTime(enabled: Boolean)

    fun isShareCardShowBrandEnabled(): Flow<Boolean>

    suspend fun setShareCardShowBrand(enabled: Boolean)
}

interface DraftPreferencesRepository {
    fun getDraftText(): Flow<String>

    suspend fun setDraftText(text: String?)
}

interface PreferencesRepository :
    DateTimePreferencesRepository,
    StoragePreferencesRepository,
    InteractionPreferencesRepository,
    MemoActionPreferencesRepository,
    SecurityPreferencesRepository,
    ShareCardPreferencesRepository,
    DraftPreferencesRepository
