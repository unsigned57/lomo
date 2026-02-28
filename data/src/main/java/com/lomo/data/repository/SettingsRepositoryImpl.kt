package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.util.StorageFilenameFormats
import com.lomo.domain.util.StorageTimestampFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dao: MemoDao,
        private val dataSource: WorkspaceConfigSource,
        private val dataStore: LomoDataStore,
    ) : AppConfigRepository {
        override fun getRootDirectory(): Flow<String?> = dataSource.getRootFlow()

        override suspend fun getRootDirectoryOnce(): String? = dataStore.getRootDirectoryOnce()

        override fun getRootDisplayName(): Flow<String?> = dataSource.getRootDisplayNameFlow()

        override fun getImageDirectory(): Flow<String?> = dataSource.getImageRootFlow()

        override fun getImageDisplayName(): Flow<String?> = dataSource.getImageRootDisplayNameFlow()

        override fun getVoiceDirectory(): Flow<String?> = dataSource.getVoiceRootFlow()

        override fun getVoiceDisplayName(): Flow<String?> = dataSource.getVoiceRootDisplayNameFlow()

        override suspend fun setRootDirectory(path: String) {
            dataSource.setRoot(path)
            clearMemoCaches()
        }

        override suspend fun setImageDirectory(path: String) {
            dataSource.setImageRoot(path)
        }

        override suspend fun setVoiceDirectory(path: String) {
            dataSource.setVoiceRoot(path)
        }

        override suspend fun updateRootUri(uri: String?) {
            dataStore.updateRootUri(uri)
            clearMemoCaches()
        }

        override suspend fun updateImageUri(uri: String?) {
            dataStore.updateImageUri(uri)
        }

        override suspend fun updateVoiceUri(uri: String?) {
            dataStore.updateVoiceUri(uri)
        }

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

        override fun getStorageFilenameFormat(): Flow<String> = dataStore.storageFilenameFormat

        override suspend fun setStorageFilenameFormat(format: String) {
            dataStore.updateStorageFilenameFormat(StorageFilenameFormats.normalize(format))
        }

        override fun getStorageTimestampFormat(): Flow<String> = dataStore.storageTimestampFormat

        override suspend fun setStorageTimestampFormat(format: String) {
            dataStore.updateStorageTimestampFormat(StorageTimestampFormats.normalize(format))
        }

        override fun isHapticFeedbackEnabled(): Flow<Boolean> = dataStore.hapticFeedbackEnabled

        override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
            dataStore.updateHapticFeedbackEnabled(enabled)
        }

        override fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean> = dataStore.checkUpdatesOnStartup

        override suspend fun setCheckUpdatesOnStartup(enabled: Boolean) {
            dataStore.updateCheckUpdatesOnStartup(enabled)
        }

        override fun isShowInputHintsEnabled(): Flow<Boolean> = dataStore.showInputHints

        override suspend fun setShowInputHints(enabled: Boolean) {
            dataStore.updateShowInputHints(enabled)
        }

        override fun isDoubleTapEditEnabled(): Flow<Boolean> = dataStore.doubleTapEditEnabled

        override suspend fun setDoubleTapEditEnabled(enabled: Boolean) {
            dataStore.updateDoubleTapEditEnabled(enabled)
        }

        override fun getShareCardStyle(): Flow<ShareCardStyle> = dataStore.shareCardStyle.map { ShareCardStyle.fromValue(it) }

        override suspend fun setShareCardStyle(style: ShareCardStyle) {
            dataStore.updateShareCardStyle(style.value)
        }

        override fun isShareCardShowTimeEnabled(): Flow<Boolean> = dataStore.shareCardShowTime

        override suspend fun setShareCardShowTime(enabled: Boolean) {
            dataStore.updateShareCardShowTime(enabled)
        }

        override fun isShareCardShowBrandEnabled(): Flow<Boolean> = dataStore.shareCardShowBrand

        override suspend fun setShareCardShowBrand(enabled: Boolean) {
            dataStore.updateShareCardShowBrand(enabled)
        }

        private suspend fun clearMemoCaches() {
            dao.clearMemoFileOutbox()
            dao.clearLocalFileState()
            dao.clearTagRefs()
            dao.clearAll()
            dao.clearTrash()
            dao.clearFts()
        }
    }
