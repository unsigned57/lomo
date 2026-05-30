package com.lomo.domain.repository

import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
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
}

interface InteractionBehaviorPreferencesRepository {
    fun isQuickSaveOnBackEnabled(): Flow<Boolean>

    suspend fun setQuickSaveOnBackEnabled(enabled: Boolean)

    fun isScrollbarEnabled(): Flow<Boolean>

    suspend fun setScrollbarEnabled(enabled: Boolean)
}

interface MemoActionPreferencesRepository {
    companion object {
        const val DEFAULT_MEMO_ACTION_ORDER_SCOPE = "main"
    }

    fun isMemoActionAutoReorderEnabled(): Flow<Boolean>

    suspend fun setMemoActionAutoReorderEnabled(enabled: Boolean)

    fun getMemoActionOrder(): Flow<List<String>> =
        getMemoActionOrder(DEFAULT_MEMO_ACTION_ORDER_SCOPE)

    fun getMemoActionOrder(scope: String): Flow<List<String>>

    fun getMemoActionOrdersByScope(): Flow<Map<String, List<String>>>

    suspend fun updateMemoActionOrder(actionOrder: List<String>) {
        updateMemoActionOrder(DEFAULT_MEMO_ACTION_ORDER_SCOPE, actionOrder)
    }

    suspend fun updateMemoActionOrder(
        scope: String,
        actionOrder: List<String>,
    )
}

interface InputToolbarPreferencesRepository {
    fun getInputToolbarToolOrder(): Flow<List<String>>

    suspend fun updateInputToolbarToolOrder(toolOrder: List<String>)
}

interface SidebarTagOrderPreferencesRepository {
    fun getSidebarTagOrder(): Flow<List<String>>

    suspend fun updateSidebarTagOrder(order: List<String>)
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

    fun getShareCardSignatureText(): Flow<String>

    suspend fun setShareCardSignatureText(text: String)
}

interface SyncInboxPreferencesRepository {
    fun isSyncInboxEnabled(): Flow<Boolean>

    suspend fun setSyncInboxEnabled(enabled: Boolean)
}

interface MemoSnapshotPreferencesRepository {
    fun isMemoSnapshotsEnabled(): Flow<Boolean>

    suspend fun setMemoSnapshotsEnabled(enabled: Boolean)

    fun getMemoSnapshotMaxCount(): Flow<Int>

    suspend fun setMemoSnapshotMaxCount(count: Int)

    fun getMemoSnapshotMaxAgeDays(): Flow<Int>

    suspend fun setMemoSnapshotMaxAgeDays(days: Int)
}

interface DraftPreferencesRepository {
    fun getDraftText(): Flow<String>

    suspend fun setDraftText(text: String?)
}

interface TypographyPreferencesRepository {
    fun getFontSizeScale(): Flow<Float>

    suspend fun setFontSizeScale(scale: Float)

    fun getLineHeightScale(): Flow<Float>

    suspend fun setLineHeightScale(scale: Float)

    fun getLetterSpacingScale(): Flow<Float>

    suspend fun setLetterSpacingScale(scale: Float)

    fun getParagraphSpacingScale(): Flow<Float>

    suspend fun setParagraphSpacingScale(scale: Float)
}

interface ColorSchemePreferencesRepository {
    fun getColorSource(): Flow<ColorSource>

    suspend fun setColorSource(source: ColorSource)

    fun getColorHistory(): Flow<List<Int>>

    suspend fun addColorToHistory(argb: Int)
}

interface FontPreferencesRepository {
    fun getFontPreference(): Flow<FontPreference>

    suspend fun setFontPreference(preference: FontPreference)
}

interface PreferencesRepository :
    DateTimePreferencesRepository,
    StoragePreferencesRepository,
    InteractionPreferencesRepository,
    InteractionBehaviorPreferencesRepository,
    MemoActionPreferencesRepository,
    InputToolbarPreferencesRepository,
    SidebarTagOrderPreferencesRepository,
    SecurityPreferencesRepository,
    ShareCardPreferencesRepository,
    SyncInboxPreferencesRepository,
    DraftPreferencesRepository,
    TypographyPreferencesRepository,
    ColorSchemePreferencesRepository,
    FontPreferencesRepository
