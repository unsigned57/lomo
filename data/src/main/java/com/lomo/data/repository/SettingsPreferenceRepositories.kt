package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.DateTimePreferencesRepository
import com.lomo.domain.repository.DraftPreferencesRepository
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val MEMO_ACTION_ORDER_DELIMITER = "|"
private const val INPUT_TOOLBAR_TOOL_ORDER_DELIMITER = "|"
private const val SIDEBAR_TAG_ORDER_DELIMITER = "|"
private val memoActionOrderScopeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class MemoActionOrdersByScopePayload(
    val orders: Map<String, List<String>> = emptyMap(),
)

private fun decodeMemoActionOrder(serialized: String): List<String> =
    serialized
        .split(MEMO_ACTION_ORDER_DELIMITER)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private fun encodeMemoActionOrder(actionOrder: List<String>): String =
    actionOrder
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(MEMO_ACTION_ORDER_DELIMITER)

private fun normalizeMemoActionOrder(actionOrder: List<String>): List<String> =
    decodeMemoActionOrder(encodeMemoActionOrder(actionOrder))

private fun normalizeMemoActionOrderScope(scope: String): String? =
    scope.trim().takeIf(String::isNotEmpty)

private fun decodeMemoActionOrdersByScope(serialized: String): Map<String, List<String>> =
    if (serialized.isBlank()) {
        emptyMap()
    } else {
        runCatching {
            memoActionOrderScopeJson
                .decodeFromString<MemoActionOrdersByScopePayload>(serialized)
                .orders
                .mapNotNull { (scope, order) ->
                    val normalizedScope = normalizeMemoActionOrderScope(scope) ?: return@mapNotNull null
                    normalizedScope to normalizeMemoActionOrder(order)
                }.toMap()
        }.getOrDefault(emptyMap())
    }

private fun encodeMemoActionOrdersByScope(ordersByScope: Map<String, List<String>>): String =
    memoActionOrderScopeJson.encodeToString(
        MemoActionOrdersByScopePayload(
            orders =
                ordersByScope
                    .mapNotNull { (scope, order) ->
                        val normalizedScope = normalizeMemoActionOrderScope(scope) ?: return@mapNotNull null
                        val normalizedOrder = normalizeMemoActionOrder(order)
                        if (normalizedOrder.isEmpty()) {
                            null
                        } else {
                            normalizedScope to normalizedOrder
                        }
                    }.toMap(),
        ),
    )

private fun decodeInputToolbarToolOrder(serialized: String): List<String> =
    serialized
        .split(INPUT_TOOLBAR_TOOL_ORDER_DELIMITER)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private fun encodeInputToolbarToolOrder(toolOrder: List<String>): String =
    toolOrder
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(INPUT_TOOLBAR_TOOL_ORDER_DELIMITER)

private fun decodeSidebarTagOrder(serialized: String): List<String> =
    serialized
        .split(SIDEBAR_TAG_ORDER_DELIMITER)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private fun encodeSidebarTagOrder(order: List<String>): String =
    order
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(SIDEBAR_TAG_ORDER_DELIMITER)

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
        TypographyPreferencesRepository by typographyPreferencesRepository

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
                normalizeMemoActionOrderScope(scope)
                    ?: MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE
            return if (normalizedScope == MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE) {
                dataStore.memoActionOrder.map(::decodeMemoActionOrder)
            } else {
                getMemoActionOrdersByScope().map { ordersByScope -> ordersByScope[normalizedScope].orEmpty() }
            }
        }

        override fun getMemoActionOrdersByScope(): Flow<Map<String, List<String>>> =
            combine(
                dataStore.memoActionOrder.map(::decodeMemoActionOrder),
                dataStore.memoActionOrdersByScope.map(::decodeMemoActionOrdersByScope),
            ) { mainOrder, scopedOrders ->
                buildMap {
                    putAll(scopedOrders)
                    if (mainOrder.isNotEmpty()) {
                        put(MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE, mainOrder)
                    }
                }
            }

        override suspend fun updateMemoActionOrder(actionOrder: List<String>) {
            dataStore.updateMemoActionOrder(encodeMemoActionOrder(actionOrder))
        }

        override suspend fun updateMemoActionOrder(
            scope: String,
            actionOrder: List<String>,
        ) {
            val normalizedScope =
                normalizeMemoActionOrderScope(scope)
                    ?: MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE
            if (normalizedScope == MemoActionPreferencesRepository.DEFAULT_MEMO_ACTION_ORDER_SCOPE) {
                updateMemoActionOrder(actionOrder)
                return
            }
            val ordersByScope =
                decodeMemoActionOrdersByScope(dataStore.memoActionOrdersByScope.first())
                    .toMutableMap()
            val normalizedOrder = normalizeMemoActionOrder(actionOrder)
            if (normalizedOrder.isEmpty()) {
                ordersByScope.remove(normalizedScope)
            } else {
                ordersByScope[normalizedScope] = normalizedOrder
            }
            dataStore.updateMemoActionOrdersByScope(encodeMemoActionOrdersByScope(ordersByScope))
        }
    }

@Singleton
class InputToolbarPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : InputToolbarPreferencesRepository {
        override fun getInputToolbarToolOrder(): Flow<List<String>> =
            dataStore.inputToolbarToolOrder.map(::decodeInputToolbarToolOrder)

        override suspend fun updateInputToolbarToolOrder(toolOrder: List<String>) {
            dataStore.updateInputToolbarToolOrder(encodeInputToolbarToolOrder(toolOrder))
        }
    }

@Singleton
class SidebarTagOrderPreferencesRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : SidebarTagOrderPreferencesRepository {
        override fun getSidebarTagOrder(): Flow<List<String>> =
            dataStore.sidebarTagOrder.map(::decodeSidebarTagOrder)

        override suspend fun updateSidebarTagOrder(order: List<String>) {
            dataStore.updateSidebarTagOrder(encodeSidebarTagOrder(order))
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
