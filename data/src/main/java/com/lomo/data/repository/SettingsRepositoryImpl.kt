package com.lomo.data.repository

import com.lomo.data.local.dao.ImageCacheDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.share.ShareAuthUtils
import com.lomo.data.source.FileDataSource
import com.lomo.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dao: MemoDao,
        private val imageCacheDao: ImageCacheDao,
        private val dataSource: FileDataSource,
        private val dataStore: LomoDataStore,
    ) : SettingsRepository {
        override suspend fun setRootDirectory(path: String) {
            clearMemoCaches()
            dataSource.setRoot(path)
        }

        override suspend fun setImageDirectory(path: String) {
            dataSource.setImageRoot(path)
        }

        override suspend fun setVoiceDirectory(path: String) {
            dataSource.setVoiceRoot(path)
        }

        override suspend fun updateRootUri(uri: String?) {
            clearMemoCaches()
            dataStore.updateRootUri(uri)
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

        override fun getThemeMode(): Flow<String> = dataStore.themeMode

        override suspend fun setThemeMode(mode: String) {
            dataStore.updateThemeMode(mode)
        }

        override fun getStorageFilenameFormat(): Flow<String> = dataStore.storageFilenameFormat

        override suspend fun setStorageFilenameFormat(format: String) {
            dataStore.updateStorageFilenameFormat(format)
        }

        override fun getStorageTimestampFormat(): Flow<String> = dataStore.storageTimestampFormat

        override suspend fun setStorageTimestampFormat(format: String) {
            dataStore.updateStorageTimestampFormat(format)
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

        override fun isLanSharePairingConfigured(): Flow<Boolean> = dataStore.lanSharePairingKeyHex.map { ShareAuthUtils.isValidKeyHex(it) }

        override suspend fun setLanSharePairingCode(pairingCode: String) {
            val keyMaterial =
                ShareAuthUtils.deriveKeyMaterialFromPairingCode(pairingCode)
                    ?: throw IllegalArgumentException("Pairing code must be 6-64 characters")
            dataStore.updateLanSharePairingKeyHex(keyMaterial)
        }

        override suspend fun clearLanSharePairingCode() {
            dataStore.updateLanSharePairingKeyHex(null)
        }

        override fun getShareCardStyle(): Flow<String> = dataStore.shareCardStyle

        override suspend fun setShareCardStyle(style: String) {
            dataStore.updateShareCardStyle(style)
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
            dao.clearTagRefs()
            dao.clearAll()
            dao.clearTrash()
            dao.clearFts()
            imageCacheDao.clearAll()
        }
    }
