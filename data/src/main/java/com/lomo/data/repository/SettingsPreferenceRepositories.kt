package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.DateTimePreferencesRepository
import com.lomo.domain.repository.DraftPreferencesRepository
import com.lomo.domain.repository.InteractionBehaviorPreferencesRepository
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoActionPreferencesRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.repository.ShareCardPreferencesRepository
import com.lomo.domain.repository.StoragePreferencesRepository
import com.lomo.domain.repository.SyncInboxPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val MEMO_ACTION_ORDER_DELIMITER = "|"

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

@Singleton
class PreferencesRepositoryImpl
    @Inject
    constructor(
        dateTimePreferencesRepository: DateTimePreferencesRepositoryImpl,
        storagePreferencesRepository: StoragePreferencesRepositoryImpl,
        interactionPreferencesRepository: InteractionPreferencesRepositoryImpl,
        interactionBehaviorPreferencesRepository: InteractionBehaviorPreferencesRepositoryImpl,
        memoActionPreferencesRepository: MemoActionPreferencesRepositoryImpl,
        securityPreferencesRepository: SecurityPreferencesRepositoryImpl,
        shareCardPreferencesRepository: ShareCardPreferencesRepositoryImpl,
        syncInboxPreferencesRepository: SyncInboxPreferencesRepositoryImpl,
        draftPreferencesRepository: DraftPreferencesRepositoryImpl,
    ) : PreferencesRepository,
        DateTimePreferencesRepository by dateTimePreferencesRepository,
        StoragePreferencesRepository by storagePreferencesRepository,
        InteractionPreferencesRepository by interactionPreferencesRepository,
        InteractionBehaviorPreferencesRepository by interactionBehaviorPreferencesRepository,
        MemoActionPreferencesRepository by memoActionPreferencesRepository,
        SecurityPreferencesRepository by securityPreferencesRepository,
        ShareCardPreferencesRepository by shareCardPreferencesRepository,
        SyncInboxPreferencesRepository by syncInboxPreferencesRepository,
        DraftPreferencesRepository by draftPreferencesRepository

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

        override fun getMemoActionOrder(): Flow<List<String>> =
            dataStore.memoActionOrder.map(::decodeMemoActionOrder)

        override suspend fun updateMemoActionOrder(actionOrder: List<String>) {
            dataStore.updateMemoActionOrder(encodeMemoActionOrder(actionOrder))
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
