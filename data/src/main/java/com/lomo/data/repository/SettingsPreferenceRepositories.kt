package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.DateTimePreferencesRepository
import com.lomo.domain.repository.DraftPreferencesRepository
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.repository.ShareCardPreferencesRepository
import com.lomo.domain.repository.StoragePreferencesRepository
import kotlinx.coroutines.flow.Flow
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
        securityPreferencesRepository: SecurityPreferencesRepositoryImpl,
        shareCardPreferencesRepository: ShareCardPreferencesRepositoryImpl,
        draftPreferencesRepository: DraftPreferencesRepositoryImpl,
    ) : PreferencesRepository,
        DateTimePreferencesRepository by dateTimePreferencesRepository,
        StoragePreferencesRepository by storagePreferencesRepository,
        InteractionPreferencesRepository by interactionPreferencesRepository,
        SecurityPreferencesRepository by securityPreferencesRepository,
        ShareCardPreferencesRepository by shareCardPreferencesRepository,
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

        override fun isQuickSaveOnBackEnabled(): Flow<Boolean> = dataStore.quickSaveOnBackEnabled

        override suspend fun setQuickSaveOnBackEnabled(enabled: Boolean) {
            dataStore.updateQuickSaveOnBackEnabled(enabled)
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
