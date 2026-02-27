package com.lomo.domain.repository

import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    fun getDateFormat(): Flow<String>

    suspend fun setDateFormat(format: String)

    fun getTimeFormat(): Flow<String>

    suspend fun setTimeFormat(format: String)

    fun getThemeMode(): Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    fun getStorageFilenameFormat(): Flow<String>

    suspend fun setStorageFilenameFormat(format: String)

    fun getStorageTimestampFormat(): Flow<String>

    suspend fun setStorageTimestampFormat(format: String)

    fun isHapticFeedbackEnabled(): Flow<Boolean>

    suspend fun setHapticFeedbackEnabled(enabled: Boolean)

    fun isShowInputHintsEnabled(): Flow<Boolean>

    suspend fun setShowInputHints(enabled: Boolean)

    fun isDoubleTapEditEnabled(): Flow<Boolean>

    suspend fun setDoubleTapEditEnabled(enabled: Boolean)

    fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean>

    suspend fun setCheckUpdatesOnStartup(enabled: Boolean)

    fun getShareCardStyle(): Flow<ShareCardStyle>

    suspend fun setShareCardStyle(style: ShareCardStyle)

    fun isShareCardShowTimeEnabled(): Flow<Boolean>

    suspend fun setShareCardShowTime(enabled: Boolean)

    fun isShareCardShowBrandEnabled(): Flow<Boolean>

    suspend fun setShareCardShowBrand(enabled: Boolean)
}
