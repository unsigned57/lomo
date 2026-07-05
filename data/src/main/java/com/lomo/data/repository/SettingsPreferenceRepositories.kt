package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.ColorSchemePreferencesRepository
import com.lomo.domain.repository.DateTimePreferencesRepository
import com.lomo.domain.repository.DraftPreferencesRepository
import com.lomo.domain.repository.FontPreferencesRepository
import com.lomo.domain.repository.InteractionBehaviorPreferencesRepository
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.InputToolbarPreferencesRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoActionPreferencesRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.repository.ShareCardPreferencesRepository
import com.lomo.domain.repository.SidebarTagOrderPreferencesRepository
import com.lomo.domain.repository.StoragePreferencesRepository
import com.lomo.domain.repository.SyncInboxPreferencesRepository
import com.lomo.domain.repository.TypographyPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesRepositoryImpl
    @Inject
    constructor(
        dateTimePreferencesRepository: DateTimePreferencesRepositoryImpl,
        storagePreferencesRepository: StoragePreferencesRepositoryImpl,
        interactionPreferencesRepository: InteractionPreferencesRepositoryImpl,
        interactionBehaviorPreferencesRepository: InteractionBehaviorPreferencesRepositoryImpl,
        memoActionPreferencesRepository: MemoActionPreferencesRepositoryImpl,
        inputToolbarPreferencesRepository: InputToolbarPreferencesRepositoryImpl,
        sidebarTagOrderPreferencesRepository: SidebarTagOrderPreferencesRepositoryImpl,
        securityPreferencesRepository: SecurityPreferencesRepositoryImpl,
        shareCardPreferencesRepository: ShareCardPreferencesRepositoryImpl,
        syncInboxPreferencesRepository: SyncInboxPreferencesRepositoryImpl,
        draftPreferencesRepository: DraftPreferencesRepositoryImpl,
        typographyPreferencesRepository: TypographyPreferencesRepositoryImpl,
        colorSchemePreferencesRepository: ColorSchemePreferencesRepositoryImpl,
        fontPreferencesRepository: FontPreferencesRepositoryImpl,
    ) : PreferencesRepository,
        DateTimePreferencesRepository by dateTimePreferencesRepository,
        StoragePreferencesRepository by storagePreferencesRepository,
        InteractionPreferencesRepository by interactionPreferencesRepository,
        InteractionBehaviorPreferencesRepository by interactionBehaviorPreferencesRepository,
        MemoActionPreferencesRepository by memoActionPreferencesRepository,
        InputToolbarPreferencesRepository by inputToolbarPreferencesRepository,
        SidebarTagOrderPreferencesRepository by sidebarTagOrderPreferencesRepository,
        SecurityPreferencesRepository by securityPreferencesRepository,
        ShareCardPreferencesRepository by shareCardPreferencesRepository,
        SyncInboxPreferencesRepository by syncInboxPreferencesRepository,
        DraftPreferencesRepository by draftPreferencesRepository,
        TypographyPreferencesRepository by typographyPreferencesRepository,
        ColorSchemePreferencesRepository by colorSchemePreferencesRepository,
        FontPreferencesRepository by fontPreferencesRepository

@Singleton
class DateTimePreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : DateTimePreferencesRepository {
        override fun getDateFormat(): Flow<String> = dataStore.dateFormat

        override suspend fun setDateFormat(format: String) {
            dataStore.updateDateFormat(format)
        }

        override fun getTimeFormat(): Flow<String> = dataStore.timeFormat

        override suspend fun setTimeFormat(format: String) {
            dataStore.updateTimeFormat(format)
        }

        override fun getThemeMode(): Flow<ThemeMode> = dataStore.themeMode.map { ThemeMode.fromValue(it) }

        override suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.updateThemeMode(mode.value)
        }

        override fun getCalendarHeatmapThresholds(): Flow<CalendarHeatmapThresholds> =
            dataStore.calendarHeatmapThresholds.map(CalendarHeatmapThresholds::parseStorageValue)

        override suspend fun setCalendarHeatmapThresholds(thresholds: CalendarHeatmapThresholds) {
            dataStore.updateCalendarHeatmapThresholds(thresholds.storageValue)
        }
    }

@Singleton
class StoragePreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : StoragePreferencesRepository {
        override fun getStorageFilenameFormat(): Flow<String> = dataStore.storageFilenameFormat

        override suspend fun setStorageFilenameFormat(format: String) {
            dataStore.updateStorageFilenameFormat(StorageFilenameFormats.normalize(format))
        }

        override fun getStorageTimestampFormat(): Flow<String> = dataStore.storageTimestampFormat

        override suspend fun setStorageTimestampFormat(format: String) {
            dataStore.updateStorageTimestampFormat(StorageTimestampFormats.normalize(format))
        }
    }

@Singleton
class InteractionPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : InteractionPreferencesRepository {
        override fun isHapticFeedbackEnabled(): Flow<Boolean> = dataStore.hapticFeedbackEnabled

        override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
            dataStore.updateHapticFeedbackEnabled(enabled)
        }

        override fun isShowInputHintsEnabled(): Flow<Boolean> = dataStore.showInputHints

        override suspend fun setShowInputHintsEnabled(enabled: Boolean) {
            dataStore.updateShowInputHints(enabled)
        }

        override fun isDoubleTapEditEnabled(): Flow<Boolean> = dataStore.doubleTapEditEnabled

        override suspend fun setDoubleTapEditEnabled(enabled: Boolean) {
            dataStore.updateDoubleTapEditEnabled(enabled)
        }

        override fun isFreeTextCopyEnabled(): Flow<Boolean> = dataStore.freeTextCopyEnabled

        override suspend fun setFreeTextCopyEnabled(enabled: Boolean) {
            dataStore.updateFreeTextCopyEnabled(enabled)
        }
    }

@Singleton
class InteractionBehaviorPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : InteractionBehaviorPreferencesRepository {

        override fun isQuickSaveOnBackEnabled(): Flow<Boolean> = dataStore.quickSaveOnBackEnabled

        override suspend fun setQuickSaveOnBackEnabled(enabled: Boolean) {
            dataStore.updateQuickSaveOnBackEnabled(enabled)
        }

        override fun isScrollbarEnabled(): Flow<Boolean> = dataStore.scrollbarEnabled

        override suspend fun setScrollbarEnabled(enabled: Boolean) {
            dataStore.updateScrollbarEnabled(enabled)
        }

        override fun isAutoOpenInputOnForegroundEnabled(): Flow<Boolean> =
            dataStore.autoOpenInputOnForeground

        override suspend fun setAutoOpenInputOnForegroundEnabled(enabled: Boolean) {
            dataStore.updateAutoOpenInputOnForeground(enabled)
        }
    }

@Singleton
class MemoActionPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : MemoActionPreferencesRepository {
        override fun isMemoActionAutoReorderEnabled(): Flow<Boolean> = dataStore.memoActionAutoReorderEnabled

        override suspend fun setMemoActionAutoReorderEnabled(enabled: Boolean) {
            dataStore.updateMemoActionAutoReorderEnabled(enabled)
        }

        override fun getMemoActionOrder(scope: String): Flow<List<String>> {
            val normalizedScope =
                SettingsPreferenceCodecs.normalizeMemoActionOrderScope(scope)
                    ?: MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE
            return if (normalizedScope == MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE) {
                dataStore.memoActionOrder.map(SettingsPreferenceCodecs::decodeMemoActionOrder)
            } else {
                getMemoActionOrdersByScope().map { ordersByScope -> ordersByScope[normalizedScope].orEmpty() }
            }
        }

        override fun getMemoActionOrdersByScope(): Flow<Map<String, List<String>>> =
            combine(
                dataStore.memoActionOrder.map(SettingsPreferenceCodecs::decodeMemoActionOrder),
                dataStore.memoActionOrdersByScope.map(SettingsPreferenceCodecs::decodeMemoActionOrdersByScope),
            ) { mainOrder, scopedOrders ->
                SettingsPreferenceCodecs.withMainMemoActionScope(mainOrder, scopedOrders)
            }

        override suspend fun updateMemoActionOrder(actionOrder: List<String>) {
            dataStore.updateMemoActionOrder(SettingsPreferenceCodecs.encodeMemoActionOrder(actionOrder))
        }

        override suspend fun updateMemoActionOrder(
            scope: String,
            actionOrder: List<String>,
        ) {
            val normalizedScope =
                SettingsPreferenceCodecs.normalizeMemoActionOrderScope(scope)
                    ?: MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE
            if (normalizedScope == MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE) {
                updateMemoActionOrder(actionOrder)
                return
            }
            val ordersByScope =
                SettingsPreferenceCodecs.decodeMemoActionOrdersByScope(dataStore.memoActionOrdersByScope.first())
                    .toMutableMap()
            val normalizedOrder = SettingsPreferenceCodecs.normalizeMemoActionOrder(actionOrder)
            if (normalizedOrder.isEmpty()) {
                ordersByScope.remove(normalizedScope)
            } else {
                ordersByScope[normalizedScope] = normalizedOrder
            }
            dataStore.updateMemoActionOrdersByScope(
                SettingsPreferenceCodecs.encodeMemoActionOrdersByScope(ordersByScope),
            )
        }
    }

@Singleton
class InputToolbarPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : InputToolbarPreferencesRepository {
    override fun getInputToolbarToolOrder(): Flow<List<String>> =
            dataStore.inputToolbarToolOrder.map(SettingsPreferenceCodecs::decodeInputToolbarToolOrder)

        override suspend fun updateInputToolbarToolOrder(toolOrder: List<String>) {
            dataStore.updateInputToolbarToolOrder(SettingsPreferenceCodecs.encodeInputToolbarToolOrder(toolOrder))
        }
    }

@Singleton
class SidebarTagOrderPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : SidebarTagOrderPreferencesRepository {
    override fun getSidebarTagOrder(): Flow<List<String>> =
            dataStore.sidebarTagOrder.map(SettingsPreferenceCodecs::decodeSidebarTagOrder)

        override suspend fun updateSidebarTagOrder(order: List<String>) {
            dataStore.updateSidebarTagOrder(SettingsPreferenceCodecs.encodeSidebarTagOrder(order))
        }
    }

@Singleton
class SecurityPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : SecurityPreferencesRepository {
        override fun isAppLockEnabled(): Flow<Boolean> = dataStore.appLockEnabled

        override suspend fun setAppLockEnabled(enabled: Boolean) {
            dataStore.updateAppLockEnabled(enabled)
        }

        override fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean> = dataStore.checkUpdatesOnStartup

        override suspend fun setCheckUpdatesOnStartup(enabled: Boolean) {
            dataStore.updateCheckUpdatesOnStartup(enabled)
        }
    }

@Singleton
class ShareCardPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : ShareCardPreferencesRepository {
        override fun isShareCardShowTimeEnabled(): Flow<Boolean> = dataStore.shareCardShowTime

        override suspend fun setShareCardShowTime(enabled: Boolean) {
            dataStore.updateShareCardShowTime(enabled)
        }

        override fun isShareCardShowBrandEnabled(): Flow<Boolean> = dataStore.shareCardShowBrand

        override suspend fun setShareCardShowBrand(enabled: Boolean) {
            dataStore.updateShareCardShowBrand(enabled)
        }

        override fun getShareCardSignatureText(): Flow<String> = dataStore.shareCardSignatureText

        override suspend fun setShareCardSignatureText(text: String) {
            dataStore.updateShareCardSignatureText(text)
        }
    }

@Singleton
class DraftPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : DraftPreferencesRepository {
        override fun getDraftText(): Flow<String> = dataStore.draftText

        override suspend fun setDraftText(text: String?) {
            dataStore.updateDraftText(text)
        }
    }

@Singleton
class SyncInboxPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : SyncInboxPreferencesRepository {
        override fun isSyncInboxEnabled(): Flow<Boolean> = dataStore.syncInboxEnabled

        override suspend fun setSyncInboxEnabled(enabled: Boolean) {
            dataStore.updateSyncInboxEnabled(enabled)
        }
    }

@Singleton
class MemoSnapshotPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : MemoSnapshotPreferencesRepository {
        override fun isMemoSnapshotsEnabled(): Flow<Boolean> = dataStore.memoSnapshotsEnabled

        override suspend fun setMemoSnapshotsEnabled(enabled: Boolean) {
            dataStore.updateMemoSnapshotsEnabled(enabled)
        }

        override fun getMemoSnapshotMaxCount(): Flow<Int> = dataStore.memoSnapshotMaxCount

        override suspend fun setMemoSnapshotMaxCount(count: Int) {
            dataStore.updateMemoSnapshotMaxCount(count)
        }

        override fun getMemoSnapshotMaxAgeDays(): Flow<Int> = dataStore.memoSnapshotMaxAgeDays

        override suspend fun setMemoSnapshotMaxAgeDays(days: Int) {
            dataStore.updateMemoSnapshotMaxAgeDays(days)
        }
    }

@Singleton
class TypographyPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : TypographyPreferencesRepository {
        override fun getFontSizeScale(): Flow<Float> = dataStore.fontSizeScale

        override suspend fun setFontSizeScale(scale: Float) {
            dataStore.updateFontSizeScale(scale)
        }

        override fun getLineHeightScale(): Flow<Float> = dataStore.lineHeightScale

        override suspend fun setLineHeightScale(scale: Float) {
            dataStore.updateLineHeightScale(scale)
        }

        override fun getLetterSpacingScale(): Flow<Float> = dataStore.letterSpacingScale

        override suspend fun setLetterSpacingScale(scale: Float) {
            dataStore.updateLetterSpacingScale(scale)
        }

        override fun getParagraphSpacingScale(): Flow<Float> = dataStore.paragraphSpacingScale

        override suspend fun setParagraphSpacingScale(scale: Float) {
            dataStore.updateParagraphSpacingScale(scale)
        }
    }

@Singleton
class ColorSchemePreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : ColorSchemePreferencesRepository {
        override fun getColorSource(): Flow<ColorSource> =
            dataStore.colorSource.map(ColorSource::fromStorageValue)

        override suspend fun setColorSource(source: ColorSource) {
            dataStore.updateColorSource(source.storageValue)
            if (source is ColorSource.CustomSeed) {
                dataStore.addColorToHistory(source.argb)
            }
        }

        override fun getColorHistory(): Flow<List<Int>> =
            dataStore.colorHistory.map { str ->
                str.split(",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
            }

        override suspend fun addColorToHistory(argb: Int) {
            dataStore.addColorToHistory(argb)
        }
    }

@Singleton
class FontPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : FontPreferencesRepository {
        override fun getFontPreference(): Flow<FontPreference> =
            dataStore.fontPreference.map(FontPreference::fromStorageValue)

        override suspend fun setFontPreference(preference: FontPreference) {
            dataStore.updateFontPreference(preference.storageValue)
        }
    }
