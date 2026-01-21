package com.lomo.data.repository

import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import com.lomo.domain.AppConfig
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class MemoRepositoryImpl
@Inject
constructor(
        private val dao: MemoDao,
        private val imageCacheDao: com.lomo.data.local.dao.ImageCacheDao,
        private val dataSource: FileDataSource,
        private val synchronizer: MemoSynchronizer,
        private val parser: MarkdownParser,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore
) : MemoRepository {

    override suspend fun setRootDirectory(path: String) {
        // Clear entire database cache when switching root directory
        // This ensures data from the previous directory doesn't persist
        dao.clearAll()
        dao.clearFts()
        dao.clearTags()
        dao.clearCrossRefs()
        imageCacheDao.clearAll()
        
        dataSource.setRoot(path)
    }

    override suspend fun setImageDirectory(path: String) {
        dataSource.setImageRoot(path)
    }

    override fun getRootDirectory(): Flow<String?> = dataSource.getRootFlow()
    override suspend fun getRootDirectoryOnce(): String? = dataStore.getRootDirectoryOnce()
    override fun getRootDisplayName(): Flow<String?> = dataSource.getRootDisplayNameFlow()

    override fun getImageDirectory(): Flow<String?> = dataSource.getImageRootFlow()
    override fun getImageDisplayName(): Flow<String?> = dataSource.getImageRootDisplayNameFlow()

    override fun getVoiceDirectory(): Flow<String?> = dataSource.getVoiceRootFlow()
    override fun getVoiceDisplayName(): Flow<String?> = dataSource.getVoiceRootDisplayNameFlow()

    override suspend fun updateRootUri(uri: String?) {
        // Clear entire database cache when switching root directory
        dao.clearAll()
        dao.clearFts()
        dao.clearTags()
        dao.clearCrossRefs()
        imageCacheDao.clearAll()

        dataStore.updateRootUri(uri)
    }

    override suspend fun updateImageUri(uri: String?) {
        dataStore.updateImageUri(uri)
    }

    override suspend fun setVoiceDirectory(path: String) {
        dataSource.setVoiceRoot(path)
    }

    override suspend fun updateVoiceUri(uri: String?) {
        dataStore.updateVoiceUri(uri)
    }

    override fun getAllMemos(): Flow<PagingData<Memo>> {
        return Pager(
                        config =
                                PagingConfig(
                                        pageSize = AppConfig.PAGE_SIZE,
                                        // Add prefetch and initial load settings
                                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                                        enablePlaceholders = false
                                ),
                        pagingSourceFactory = { dao.getAllMemos() }
                )
                .flow
                .map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override suspend fun getRandomMemos(limit: Int): List<Memo> {
        return dao.getRandomMemos(limit).map { it.toDomain() }
    }

    override suspend fun getDailyReviewMemos(limit: Int, seedDate: java.time.LocalDate): List<Memo> {
        val allIds = dao.getAllMemoIds()
        if (allIds.isEmpty()) return emptyList()
        
        // Use date hashcode as seed for consistent daily shuffling
        val seed = seedDate.toEpochDay()

        
        val selectedIds = allIds.shuffled(kotlin.random.Random(seed)).take(limit)
        return dao.getMemosByIds(selectedIds).map { it.toDomain() }
    }

    override suspend fun refreshMemos() {
        synchronizer.refresh()
    }

    override fun isSyncing(): Flow<Boolean> = synchronizer.isSyncing

    override suspend fun saveMemo(content: String, timestamp: Long) {
        synchronizer.saveMemo(content, timestamp)
    }

    override suspend fun updateMemo(memo: Memo, newContent: String) {
        synchronizer.updateMemo(memo, newContent)
    }

    // updateLines removed - moved logic to MemoTextProcessor and ViewModel

    override suspend fun deleteMemo(memo: Memo) {
        synchronizer.deleteMemo(memo)
    }

    override fun searchMemos(query: String): Flow<PagingData<Memo>> {
        return Pager(
                        config =
                                PagingConfig(
                                        pageSize = AppConfig.PAGE_SIZE,
                                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                                        enablePlaceholders = false
                                ),
                        pagingSourceFactory = {
                            // Use standard LIKE search for stability.
                            // The DAO handles the wrapping with wildcards '%'
                            dao.searchMemos(query)
                        }
                )
                .flow
                .map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override fun getMemosByTag(tag: String): Flow<PagingData<Memo>> {
        return Pager(
                        config =
                                PagingConfig(
                                        pageSize = AppConfig.PAGE_SIZE,
                                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                                        enablePlaceholders = false
                                ),
                        pagingSourceFactory = { dao.getMemosByTag(tag) }
                )
                .flow
                .map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override fun getAllTags(): Flow<List<String>> {
        return dao.getAllTags().map { entities -> entities.map { it.name } }
    }

    override fun getMemoCount(): Flow<Int> = dao.getMemoCount()

    override fun getAllTimestamps(): Flow<List<Long>> = dao.getAllTimestamps()

    override fun getTagCounts(): Flow<List<com.lomo.domain.model.TagCount>> = dao.getTagCounts()

    override fun getDeletedMemos(): Flow<PagingData<Memo>> {
        return Pager(
                        config =
                                PagingConfig(
                                        pageSize = AppConfig.PAGE_SIZE,
                                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                                        enablePlaceholders = false
                                ),
                        pagingSourceFactory = { dao.getDeletedMemos() }
                )
                .flow
                .map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override suspend fun restoreMemo(memo: Memo) {
        synchronizer.restoreMemo(memo)
    }

    override suspend fun deletePermanently(memo: Memo) {
        synchronizer.deletePermanently(memo)
    }

    override suspend fun saveImage(uri: Uri): String {
        return dataSource.saveImage(uri)
    }

    override suspend fun createVoiceFile(filename: String): Uri {
        return dataSource.createVoiceFile(filename)
    }

    override suspend fun deleteVoiceFile(filename: String) {
        dataSource.deleteVoiceFile(filename)
    }

    override fun getImageUriMap(): Flow<Map<String, String>> {
        return imageCacheDao.getAllImages().map { entities ->
            entities.associate { it.filename to it.uriString }
        }
    }

    override suspend fun syncImageCache() {
        // Sync logic: Differential sync instead of clear-and-insert-all
        if (dataSource.getImageRootFlow().first() == null) return

        // Get current state from both sides
        val existingImages = imageCacheDao.getAllImagesSync()
        val existingMap = existingImages.associateBy { it.filename }
        val existingFilenames = existingMap.keys

        val newImages = dataSource.listImageFiles()
        val newFilenames = newImages.map { it.first }.toSet()

        // Calculate diff
        val toInsert = newImages.filter { it.first !in existingMap }
        val toDelete = existingFilenames - newFilenames

        // Apply changes
        if (toDelete.isNotEmpty()) {
            imageCacheDao.deleteByFilenames(toDelete.toList())
        }

        if (toInsert.isNotEmpty()) {
            val cacheEntities =
                    toInsert.map { (name, uri) ->
                        com.lomo.data.local.entity.ImageCacheEntity(
                                filename = name,
                                uriString = uri,
                                lastModified = System.currentTimeMillis()
                        )
                    }
            imageCacheDao.insertImages(cacheEntities)
        }
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

    override fun isHapticFeedbackEnabled(): Flow<Boolean> = dataStore.hapticFeedbackEnabled

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        dataStore.updateHapticFeedbackEnabled(enabled)
    }

    override fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean> = dataStore.checkUpdatesOnStartup

    override suspend fun setCheckUpdatesOnStartup(enabled: Boolean) {
        dataStore.updateCheckUpdatesOnStartup(enabled)
    }

    override fun getStorageFilenameFormat(): Flow<String> = dataStore.storageFilenameFormat

    override suspend fun setStorageFilenameFormat(format: String) {
        dataStore.updateStorageFilenameFormat(format)
    }

    override fun getStorageTimestampFormat(): Flow<String> = dataStore.storageTimestampFormat

    override suspend fun setStorageTimestampFormat(format: String) {
        dataStore.updateStorageTimestampFormat(format)
    }

    override suspend fun createDefaultImageDirectory(): String? {
        return try {
            val uri = dataSource.createDirectory("images")
            setImageDirectory(uri)
            uri
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun createDefaultVoiceDirectory(): String? {
        return try {
            val uri = dataSource.createDirectory("voice")
            setVoiceDirectory(uri)
            uri
        } catch (e: Exception) {
            null
        }
    }
}
