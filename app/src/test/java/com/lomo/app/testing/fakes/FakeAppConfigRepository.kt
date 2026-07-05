package com.lomo.app.testing.fakes

import com.lomo.domain.model.AppPreferenceSnapshot
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import com.lomo.domain.repository.MemoActionPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Hand-written fake for [AppConfigRepository] used by app/test ViewModel and coordinator
 * specs. Backing values default to [PreferenceDefaults] so specs only need to override the
 * preferences relevant to the scenario under test.
 *
 * Mutation helpers — [setLocation], [setThemeMode], [setMemoActionOrder], etc. — should be
 * preferred over `mockk(relaxed = true)` overrides. When a brand-new preference appears,
 * extend this class rather than wrapping it in MockK.
 */
open class FakeAppConfigRepository : AppConfigRepository, AppPreferencesSnapshotRepository {
    private val locations: Map<StorageArea, MutableStateFlow<StorageLocation?>> =
        StorageArea.values().associateWith { MutableStateFlow<StorageLocation?>(null) }
    private val displayNames: Map<StorageArea, MutableStateFlow<String?>> =
        StorageArea.values().associateWith { MutableStateFlow<String?>(null) }

    private val dateFormat = MutableStateFlow(PreferenceDefaults.DATE_FORMAT)
    private val timeFormat = MutableStateFlow(PreferenceDefaults.TIME_FORMAT)
    private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val calendarHeatmapThresholds = MutableStateFlow(CalendarHeatmapThresholds.default())
    private val colorSource = MutableStateFlow<ColorSource>(ColorSource.default())
    private val colorHistory = MutableStateFlow<List<Int>>(emptyList())
    private val fontPreference = MutableStateFlow<FontPreference>(FontPreference.default())
    private val storageFilenameFormat = MutableStateFlow(PreferenceDefaults.STORAGE_FILENAME_FORMAT)
    private val storageTimestampFormat = MutableStateFlow(PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)

    private val hapticEnabled = MutableStateFlow(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
    private val showInputHints = MutableStateFlow(PreferenceDefaults.SHOW_INPUT_HINTS)
    private val doubleTapEdit = MutableStateFlow(PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)
    private val freeTextCopy = MutableStateFlow(PreferenceDefaults.FREE_TEXT_COPY_ENABLED)

    private val quickSaveOnBack = MutableStateFlow(PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)
    private val scrollbarEnabled = MutableStateFlow(PreferenceDefaults.SCROLLBAR_ENABLED)
    private val autoOpenInputOnForeground = MutableStateFlow(PreferenceDefaults.AUTO_OPEN_INPUT_ON_FOREGROUND)

    private val memoActionAutoReorder = MutableStateFlow(PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED)
    private val memoActionOrders = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val inputToolbarToolOrder = MutableStateFlow<List<String>>(emptyList())
    private val sidebarTagOrder = MutableStateFlow<List<String>>(emptyList())

    private val appLockEnabled = MutableStateFlow(PreferenceDefaults.APP_LOCK_ENABLED)
    private val checkUpdatesOnStartup = MutableStateFlow(PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)

    private val shareCardShowTime = MutableStateFlow(PreferenceDefaults.SHARE_CARD_SHOW_TIME)
    private val shareCardShowBrand = MutableStateFlow(PreferenceDefaults.SHARE_CARD_SHOW_BRAND)
    private val shareCardSignatureText = MutableStateFlow(PreferenceDefaults.SHARE_CARD_SIGNATURE_TEXT)

    private val syncInboxEnabled = MutableStateFlow(PreferenceDefaults.SYNC_INBOX_ENABLED)

    private val draftText = MutableStateFlow("")

    private val fontSizeScale = MutableStateFlow(1f)
    private val lineHeightScale = MutableStateFlow(1f)
    private val letterSpacingScale = MutableStateFlow(1f)
    private val paragraphSpacingScale = MutableStateFlow(1f)

    fun setLocation(
        area: StorageArea,
        location: StorageLocation?,
    ) {
        locations.getValue(area).value = location
    }

    fun setDisplayName(
        area: StorageArea,
        name: String?,
    ) {
        displayNames.getValue(area).value = name
    }

    fun setThemeModeNow(mode: ThemeMode) {
        themeMode.value = mode
    }

    fun setMemoActionOrder(
        scope: String = MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE,
        order: List<String>,
    ) {
        memoActionOrders.value = memoActionOrders.value + (scope to order)
    }

    override fun observeAppPreferenceSnapshot(): Flow<AppPreferenceSnapshot> =
        combine(
            observeDisplaySnapshot(),
            observeEditorSnapshot(),
            observeShareSnapshot(),
            observeTypographySnapshot(),
        ) { display, editor, share, typography ->
            AppPreferenceSnapshot(
                dateFormat = display.dateFormat,
                timeFormat = display.timeFormat,
                themeMode = display.themeMode,
                calendarHeatmapThresholds = display.calendarHeatmapThresholds,
                colorSource = display.colorSource,
                fontPreference = display.fontPreference,
                hapticFeedbackEnabled = editor.hapticFeedbackEnabled,
                showInputHints = editor.showInputHints,
                doubleTapEditEnabled = editor.doubleTapEditEnabled,
                freeTextCopyEnabled = editor.freeTextCopyEnabled,
                memoActionAutoReorderEnabled = editor.memoActionAutoReorderEnabled,
                autoOpenInputOnForeground = editor.autoOpenInputOnForeground,
                memoActionOrder =
                    editor.memoActionOrdersByScope[
                        MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE,
                    ].orEmpty(),
                memoActionOrdersByScope = editor.memoActionOrdersByScope,
                inputToolbarToolOrder = editor.inputToolbarToolOrder,
                quickSaveOnBackEnabled = editor.quickSaveOnBackEnabled,
                scrollbarEnabled = editor.scrollbarEnabled,
                shareCardShowTime = share.shareCardShowTime,
                shareCardShowBrand = share.shareCardShowBrand,
                shareCardSignatureText = share.shareCardSignatureText,
                typographyFontSizeScale = typography.fontSizeScale,
                typographyLineHeightScale = typography.lineHeightScale,
                typographyLetterSpacingScale = typography.letterSpacingScale,
                typographyParagraphSpacingScale = typography.paragraphSpacingScale,
            )
        }

    private fun observeDisplaySnapshot(): Flow<FakeDisplaySnapshot> =
        combine(
            dateFormat,
            timeFormat,
            themeMode,
            observeDisplayPersonalizationSnapshot(),
        ) { dateFormat, timeFormat, themeMode, personalization ->
            FakeDisplaySnapshot(
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                themeMode = themeMode,
                calendarHeatmapThresholds = personalization.calendarHeatmapThresholds,
                colorSource = personalization.colorSource,
                fontPreference = personalization.fontPreference,
            )
        }

    private fun observeDisplayPersonalizationSnapshot(): Flow<FakeDisplayPersonalizationSnapshot> =
        combine(
            colorSource,
            calendarHeatmapThresholds,
            fontPreference,
        ) { colorSource, calendarHeatmapThresholds, fontPreference ->
            FakeDisplayPersonalizationSnapshot(
                colorSource = colorSource,
                calendarHeatmapThresholds = calendarHeatmapThresholds,
                fontPreference = fontPreference,
            )
        }

    private fun observeEditorSnapshot(): Flow<FakeEditorSnapshot> =
        combine(
            observeEditorToggleSnapshot(),
            memoActionOrders,
            inputToolbarToolOrder,
            observeEditorBehaviorSnapshot(),
        ) { toggles, memoActionOrders, inputToolbarToolOrder, behavior ->
            FakeEditorSnapshot(
                hapticFeedbackEnabled = toggles.hapticFeedbackEnabled,
                showInputHints = toggles.showInputHints,
                doubleTapEditEnabled = toggles.doubleTapEditEnabled,
                freeTextCopyEnabled = toggles.freeTextCopyEnabled,
                memoActionAutoReorderEnabled = toggles.memoActionAutoReorderEnabled,
                memoActionOrdersByScope = memoActionOrders,
                inputToolbarToolOrder = inputToolbarToolOrder,
                quickSaveOnBackEnabled = behavior.quickSaveOnBackEnabled,
                scrollbarEnabled = behavior.scrollbarEnabled,
                autoOpenInputOnForeground = behavior.autoOpenInputOnForeground,
            )
        }

    private fun observeEditorToggleSnapshot(): Flow<FakeEditorToggleSnapshot> =
        combine(
            hapticEnabled,
            showInputHints,
            doubleTapEdit,
            freeTextCopy,
            memoActionAutoReorder,
        ) { hapticEnabled, showInputHints, doubleTapEdit, freeTextCopy, memoActionAutoReorder ->
            FakeEditorToggleSnapshot(
                hapticFeedbackEnabled = hapticEnabled,
                showInputHints = showInputHints,
                doubleTapEditEnabled = doubleTapEdit,
                freeTextCopyEnabled = freeTextCopy,
                memoActionAutoReorderEnabled = memoActionAutoReorder,
            )
        }

    private fun observeEditorBehaviorSnapshot(): Flow<FakeEditorBehaviorSnapshot> =
        combine(
            quickSaveOnBack,
            scrollbarEnabled,
            autoOpenInputOnForeground,
        ) { quickSaveOnBack, scrollbarEnabled, autoOpenInputOnForeground ->
            FakeEditorBehaviorSnapshot(
                quickSaveOnBackEnabled = quickSaveOnBack,
                scrollbarEnabled = scrollbarEnabled,
                autoOpenInputOnForeground = autoOpenInputOnForeground,
            )
        }

    private fun observeShareSnapshot(): Flow<FakeShareSnapshot> =
        combine(
            shareCardShowTime,
            shareCardShowBrand,
            shareCardSignatureText,
        ) { shareCardShowTime, shareCardShowBrand, shareCardSignatureText ->
            FakeShareSnapshot(shareCardShowTime, shareCardShowBrand, shareCardSignatureText)
        }

    private fun observeTypographySnapshot(): Flow<FakeTypographySnapshot> =
        combine(
            fontSizeScale,
            lineHeightScale,
            letterSpacingScale,
            paragraphSpacingScale,
        ) { fontSizeScale, lineHeightScale, letterSpacingScale, paragraphSpacingScale ->
            FakeTypographySnapshot(fontSizeScale, lineHeightScale, letterSpacingScale, paragraphSpacingScale)
        }

    private val deferredLocations: Map<StorageArea, MutableStateFlow<kotlinx.coroutines.CompletableDeferred<StorageLocation?>?>> =
        StorageArea.values().associateWith { MutableStateFlow<kotlinx.coroutines.CompletableDeferred<StorageLocation?>?>(null) }

    fun setLocationDeferred(
        area: StorageArea,
        deferred: kotlinx.coroutines.CompletableDeferred<StorageLocation?>,
    ) {
        deferredLocations.getValue(area).value = deferred
    }

    override fun observeLocation(area: StorageArea): Flow<StorageLocation?> = locations.getValue(area).asStateFlow()

    override suspend fun currentLocation(area: StorageArea): StorageLocation? {
        deferredLocations.getValue(area).value?.let { return it.await() }
        return locations.getValue(area).value
    }

    override suspend fun applyLocation(update: StorageAreaUpdate) {
        locations.getValue(update.area).value = update.location
    }

    override fun observeDisplayName(area: StorageArea): Flow<String?> = displayNames.getValue(area).asStateFlow()

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

    override fun getCalendarHeatmapThresholds(): Flow<CalendarHeatmapThresholds> =
        calendarHeatmapThresholds.asStateFlow()

    override suspend fun setCalendarHeatmapThresholds(thresholds: CalendarHeatmapThresholds) {
        calendarHeatmapThresholds.value = thresholds
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
        val current = colorHistory.value.toMutableList()
        current.remove(argb)
        current.add(0, argb)
        colorHistory.value = current.take(8)
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

    override fun isHapticFeedbackEnabled(): Flow<Boolean> = hapticEnabled.asStateFlow()

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        hapticEnabled.value = enabled
    }

    override fun isShowInputHintsEnabled(): Flow<Boolean> = showInputHints.asStateFlow()

    override suspend fun setShowInputHintsEnabled(enabled: Boolean) {
        showInputHints.value = enabled
    }

    override fun isDoubleTapEditEnabled(): Flow<Boolean> = doubleTapEdit.asStateFlow()

    override suspend fun setDoubleTapEditEnabled(enabled: Boolean) {
        doubleTapEdit.value = enabled
    }

    override fun isFreeTextCopyEnabled(): Flow<Boolean> = freeTextCopy.asStateFlow()

    override suspend fun setFreeTextCopyEnabled(enabled: Boolean) {
        freeTextCopy.value = enabled
    }

    override fun isQuickSaveOnBackEnabled(): Flow<Boolean> = quickSaveOnBack.asStateFlow()

    override suspend fun setQuickSaveOnBackEnabled(enabled: Boolean) {
        quickSaveOnBack.value = enabled
    }

    override fun isScrollbarEnabled(): Flow<Boolean> = scrollbarEnabled.asStateFlow()

    override suspend fun setScrollbarEnabled(enabled: Boolean) {
        scrollbarEnabled.value = enabled
    }

    override fun isAutoOpenInputOnForegroundEnabled(): Flow<Boolean> =
        autoOpenInputOnForeground.asStateFlow()

    override suspend fun setAutoOpenInputOnForegroundEnabled(enabled: Boolean) {
        autoOpenInputOnForeground.value = enabled
    }

    override fun isMemoActionAutoReorderEnabled(): Flow<Boolean> = memoActionAutoReorder.asStateFlow()

    override suspend fun setMemoActionAutoReorderEnabled(enabled: Boolean) {
        memoActionAutoReorder.value = enabled
    }

    override fun getMemoActionOrder(scope: String): Flow<List<String>> =
        memoActionOrders.map { orders -> orders[scope] ?: emptyList() }

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

    fun setAppLockEnabledNow(enabled: Boolean) {
        appLockEnabled.value = enabled
    }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        appLockEnabled.value = enabled
    }

    override fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean> = checkUpdatesOnStartup.asStateFlow()

    override suspend fun setCheckUpdatesOnStartup(enabled: Boolean) {
        checkUpdatesOnStartup.value = enabled
    }

    override fun isShareCardShowTimeEnabled(): Flow<Boolean> = shareCardShowTime.asStateFlow()

    override suspend fun setShareCardShowTime(enabled: Boolean) {
        shareCardShowTime.value = enabled
    }

    override fun isShareCardShowBrandEnabled(): Flow<Boolean> = shareCardShowBrand.asStateFlow()

    override suspend fun setShareCardShowBrand(enabled: Boolean) {
        shareCardShowBrand.value = enabled
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
}

private data class FakeDisplaySnapshot(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val calendarHeatmapThresholds: CalendarHeatmapThresholds,
    val colorSource: ColorSource,
    val fontPreference: FontPreference,
)

private data class FakeDisplayPersonalizationSnapshot(
    val colorSource: ColorSource,
    val calendarHeatmapThresholds: CalendarHeatmapThresholds,
    val fontPreference: FontPreference,
)

private data class FakeEditorToggleSnapshot(
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
)

private data class FakeEditorBehaviorSnapshot(
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val autoOpenInputOnForeground: Boolean,
)

private data class FakeEditorSnapshot(
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrdersByScope: Map<String, List<String>>,
    val inputToolbarToolOrder: List<String>,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val autoOpenInputOnForeground: Boolean,
)

private data class FakeShareSnapshot(
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
    val shareCardSignatureText: String,
)

private data class FakeTypographySnapshot(
    val fontSizeScale: Float,
    val lineHeightScale: Float,
    val letterSpacingScale: Float,
    val paragraphSpacingScale: Float,
)
