package com.lomo.domain.testing.fakes

import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.MemoActionPreferencesRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePreferencesRepository : PreferencesRepository {
    private val dateFormat = MutableStateFlow("yyyy_MM_dd")
    private val timeFormat = MutableStateFlow("HH:mm:ss")
    private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val colorSource = MutableStateFlow<ColorSource>(ColorSource.default())
    private val colorHistory = MutableStateFlow<List<Int>>(emptyList())
    private val fontPreference = MutableStateFlow<FontPreference>(FontPreference.default())
    private val storageFilenameFormat = MutableStateFlow("yyyy_MM_dd")
    private val storageTimestampFormat = MutableStateFlow("HH:mm:ss")
    private val hapticFeedbackEnabled = MutableStateFlow(true)
    private val showInputHintsEnabled = MutableStateFlow(true)
    private val doubleTapEditEnabled = MutableStateFlow(true)
    private val freeTextCopyEnabled = MutableStateFlow(false)
    private val quickSaveOnBackEnabled = MutableStateFlow(true)
    private val scrollbarEnabled = MutableStateFlow(true)
    private val memoActionAutoReorderEnabled = MutableStateFlow(false)
    private val memoActionOrders = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val inputToolbarToolOrder = MutableStateFlow<List<String>>(emptyList())
    private val sidebarTagOrder = MutableStateFlow<List<String>>(emptyList())
    private val appLockEnabled = MutableStateFlow(false)
    private val checkUpdatesOnStartupEnabled = MutableStateFlow(true)
    private val shareCardShowTimeEnabled = MutableStateFlow(true)
    private val shareCardShowBrandEnabled = MutableStateFlow(true)
    private val shareCardSignatureText = MutableStateFlow("")
    private val syncInboxEnabled = MutableStateFlow(true)
    private val draftText = MutableStateFlow("")
    private val fontSizeScale = MutableStateFlow(1f)
    private val lineHeightScale = MutableStateFlow(1f)
    private val letterSpacingScale = MutableStateFlow(1f)
    private val paragraphSpacingScale = MutableStateFlow(1f)

    fun setCheckUpdatesOnStartupEnabled(enabled: Boolean) {
        checkUpdatesOnStartupEnabled.value = enabled
    }

    fun setSyncInboxPreferenceEnabled(enabled: Boolean) {
        syncInboxEnabled.value = enabled
    }

    override fun getDateFormat(): Flow<String> = dateFormat.asStateFlow()

    override suspend fun setDateFormat(format: String) {
        dateFormat.value = format
    }

    override fun getTimeFormat(): Flow<String> = timeFormat.asStateFlow()

    override suspend fun setTimeFormat(format: String) {
        timeFormat.value = format
    }

    override fun getThemeMode(): Flow<ThemeMode> = themeMode.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }

    override fun getColorSource(): Flow<ColorSource> = colorSource.asStateFlow()

    override suspend fun setColorSource(source: ColorSource) {
        colorSource.value = source
        if (source is ColorSource.CustomSeed) {
            addColorToHistory(source.argb)
        }
    }

    override fun getColorHistory(): Flow<List<Int>> = colorHistory.asStateFlow()

    override suspend fun addColorToHistory(argb: Int) {
        val updatedHistory = colorHistory.value.toMutableList()
        updatedHistory.remove(argb)
        updatedHistory.add(0, argb)
        colorHistory.value = updatedHistory.take(COLOR_HISTORY_LIMIT)
    }

    override fun getFontPreference(): Flow<FontPreference> = fontPreference.asStateFlow()

    override suspend fun setFontPreference(preference: FontPreference) {
        fontPreference.value = preference
    }

    override fun getStorageFilenameFormat(): Flow<String> = storageFilenameFormat.asStateFlow()

    override suspend fun setStorageFilenameFormat(format: String) {
        storageFilenameFormat.value = format
    }

    override fun getStorageTimestampFormat(): Flow<String> = storageTimestampFormat.asStateFlow()

    override suspend fun setStorageTimestampFormat(format: String) {
        storageTimestampFormat.value = format
    }

    override fun isHapticFeedbackEnabled(): Flow<Boolean> = hapticFeedbackEnabled.asStateFlow()

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        hapticFeedbackEnabled.value = enabled
    }

    override fun isShowInputHintsEnabled(): Flow<Boolean> = showInputHintsEnabled.asStateFlow()

    override suspend fun setShowInputHintsEnabled(enabled: Boolean) {
        showInputHintsEnabled.value = enabled
    }

    override fun isDoubleTapEditEnabled(): Flow<Boolean> = doubleTapEditEnabled.asStateFlow()

    override suspend fun setDoubleTapEditEnabled(enabled: Boolean) {
        doubleTapEditEnabled.value = enabled
    }

    override fun isFreeTextCopyEnabled(): Flow<Boolean> = freeTextCopyEnabled.asStateFlow()

    override suspend fun setFreeTextCopyEnabled(enabled: Boolean) {
        freeTextCopyEnabled.value = enabled
    }

    override fun isQuickSaveOnBackEnabled(): Flow<Boolean> = quickSaveOnBackEnabled.asStateFlow()

    override suspend fun setQuickSaveOnBackEnabled(enabled: Boolean) {
        quickSaveOnBackEnabled.value = enabled
    }

    override fun isScrollbarEnabled(): Flow<Boolean> = scrollbarEnabled.asStateFlow()

    override suspend fun setScrollbarEnabled(enabled: Boolean) {
        scrollbarEnabled.value = enabled
    }

    override fun isMemoActionAutoReorderEnabled(): Flow<Boolean> = memoActionAutoReorderEnabled.asStateFlow()

    override suspend fun setMemoActionAutoReorderEnabled(enabled: Boolean) {
        memoActionAutoReorderEnabled.value = enabled
    }

    override fun getMemoActionOrder(scope: String): Flow<List<String>> =
        MutableStateFlow(
            memoActionOrders.value[scope]
                ?: memoActionOrders.value[MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE]
                ?: emptyList(),
        ).asStateFlow()

    override fun getMemoActionOrdersByScope(): Flow<Map<String, List<String>>> = memoActionOrders.asStateFlow()

    override suspend fun updateMemoActionOrder(
        scope: String,
        actionOrder: List<String>,
    ) {
        memoActionOrders.value = memoActionOrders.value + (scope to actionOrder)
    }

    override fun getInputToolbarToolOrder(): Flow<List<String>> = inputToolbarToolOrder.asStateFlow()

    override suspend fun updateInputToolbarToolOrder(toolOrder: List<String>) {
        inputToolbarToolOrder.value = toolOrder
    }

    override fun getSidebarTagOrder(): Flow<List<String>> = sidebarTagOrder.asStateFlow()

    override suspend fun updateSidebarTagOrder(order: List<String>) {
        sidebarTagOrder.value = order
    }

    override fun isAppLockEnabled(): Flow<Boolean> = appLockEnabled.asStateFlow()

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        appLockEnabled.value = enabled
    }

    override fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean> = checkUpdatesOnStartupEnabled.asStateFlow()

    override suspend fun setCheckUpdatesOnStartup(enabled: Boolean) {
        checkUpdatesOnStartupEnabled.value = enabled
    }

    override fun isShareCardShowTimeEnabled(): Flow<Boolean> = shareCardShowTimeEnabled.asStateFlow()

    override suspend fun setShareCardShowTime(enabled: Boolean) {
        shareCardShowTimeEnabled.value = enabled
    }

    override fun isShareCardShowBrandEnabled(): Flow<Boolean> = shareCardShowBrandEnabled.asStateFlow()

    override suspend fun setShareCardShowBrand(enabled: Boolean) {
        shareCardShowBrandEnabled.value = enabled
    }

    override fun getShareCardSignatureText(): Flow<String> = shareCardSignatureText.asStateFlow()

    override suspend fun setShareCardSignatureText(text: String) {
        shareCardSignatureText.value = text
    }

    override fun isSyncInboxEnabled(): Flow<Boolean> = syncInboxEnabled.asStateFlow()

    override suspend fun setSyncInboxEnabled(enabled: Boolean) {
        syncInboxEnabled.value = enabled
    }

    override fun getDraftText(): Flow<String> = draftText.asStateFlow()

    override suspend fun setDraftText(text: String?) {
        draftText.value = text.orEmpty()
    }

    override fun getFontSizeScale(): Flow<Float> = fontSizeScale.asStateFlow()

    override suspend fun setFontSizeScale(scale: Float) {
        fontSizeScale.value = scale
    }

    override fun getLineHeightScale(): Flow<Float> = lineHeightScale.asStateFlow()

    override suspend fun setLineHeightScale(scale: Float) {
        lineHeightScale.value = scale
    }

    override fun getLetterSpacingScale(): Flow<Float> = letterSpacingScale.asStateFlow()

    override suspend fun setLetterSpacingScale(scale: Float) {
        letterSpacingScale.value = scale
    }

    override fun getParagraphSpacingScale(): Flow<Float> = paragraphSpacingScale.asStateFlow()

    override suspend fun setParagraphSpacingScale(scale: Float) {
        paragraphSpacingScale.value = scale
    }

    private companion object {
        const val COLOR_HISTORY_LIMIT = 8
    }
}
