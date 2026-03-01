package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataSource: WorkspaceConfigSource,
        private val dataStore: LomoDataStore,
    ) : AppConfigRepository {
        override fun observeLocation(area: StorageArea): Flow<StorageLocation?> =
            dataSource
                .getRootFlow(area.toStorageRootType())
                .map { raw -> raw?.let(::StorageLocation) }

        override suspend fun currentLocation(area: StorageArea): StorageLocation? =
            when (area) {
                StorageArea.ROOT -> dataStore.rootUri.first() ?: dataStore.rootDirectory.first()
                StorageArea.IMAGE -> dataStore.imageUri.first() ?: dataStore.imageDirectory.first()
                StorageArea.VOICE -> dataStore.voiceUri.first() ?: dataStore.voiceDirectory.first()
            }?.let(::StorageLocation)

        override fun observeDisplayName(area: StorageArea): Flow<String?> =
            dataSource.getRootDisplayNameFlow(area.toStorageRootType())

        override suspend fun applyLocation(update: StorageAreaUpdate) {
            dataSource.setRoot(
                type = update.area.toStorageRootType(),
                pathOrUri = update.location.raw,
            )
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

        override fun isAppLockEnabled(): Flow<Boolean> = dataStore.appLockEnabled

        override suspend fun setAppLockEnabled(enabled: Boolean) {
            dataStore.updateAppLockEnabled(enabled)
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

        private fun StorageArea.toStorageRootType(): StorageRootType =
            when (this) {
                StorageArea.ROOT -> StorageRootType.MAIN
                StorageArea.IMAGE -> StorageRootType.IMAGE
                StorageArea.VOICE -> StorageRootType.VOICE
            }
    }
