package com.lomo.data.repository

import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.share.ShareAuthUtils
import com.lomo.data.source.FileDataSource
import com.lomo.domain.AppConfig
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MemoRepositoryImpl
    @Inject
    constructor(
        private val dao: MemoDao,
        private val imageCacheDao: com.lomo.data.local.dao.ImageCacheDao,
        private val tokenDao: com.lomo.data.local.dao.MemoTokenDao,
        private val dataSource: FileDataSource,
        private val synchronizer: MemoSynchronizer,
        private val parser: MarkdownParser,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore,
        private val pendingOpDao: com.lomo.data.local.dao.PendingOpDao,
    ) : MemoRepository, SettingsRepository, MediaRepository {
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

        override fun getAllMemos(): Flow<PagingData<Memo>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = AppConfig.PAGE_SIZE,
                        // Add prefetch and initial load settings
                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = { dao.getAllMemos() },
            ).flow
                .map { pagingData -> pagingData.map { it.toDomain() } }

        override suspend fun getRandomMemos(limit: Int): List<Memo> = dao.getRandomMemos(limit).map { it.toDomain() }

        override suspend fun getDailyReviewMemos(
            limit: Int,
            seedDate: java.time.LocalDate,
        ): List<Memo> {
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

        override suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
            val opId =
                pendingOpDao.insert(
                    com.lomo.data.local.entity.PendingOpEntity(
                        type = "CREATE",
                        payload = content,
                        timestamp = timestamp,
                    ),
                )
            try {
                synchronizer.saveMemo(content, timestamp)
                pendingOpDao.delete(opId)
            } catch (e: Exception) {
                throw e
            }
        }

        override suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            synchronizer.updateMemo(memo, newContent)
        }

        // updateLines removed - moved logic to MemoTextProcessor and ViewModel

        override suspend fun deleteMemo(memo: Memo) {
            synchronizer.deleteMemo(memo)
        }

        override fun searchMemos(query: String): Flow<PagingData<Memo>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = AppConfig.PAGE_SIZE,
                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    val trimmed = query.trim()
                    val hasCjk = trimmed.any { com.lomo.data.util.SearchTokenizer.run { 
                        // 复用 isCJK 判定逻辑（无法直接访问，做个简版）：
                        val block = java.lang.Character.UnicodeBlock.of(it)
                        block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                        block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                        block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                        block == java.lang.Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                        block == java.lang.Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT ||
                        block == java.lang.Character.UnicodeBlock.HIRAGANA ||
                        block == java.lang.Character.UnicodeBlock.KATAKANA ||
                        block == java.lang.Character.UnicodeBlock.HANGUL_SYLLABLES
                    } }
                    if (hasCjk) {
                        // 简单 AND 拆词：按空白切分后取前 5 个词的前缀匹配
                        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.take(5)
                        if (tokens.isEmpty()) dao.searchMemos(trimmed) else tokenDao.searchByTokensAnd(tokens)
                    } else {
                        dao.searchMemos(trimmed)
                    }
                },
            ).flow
                .map { pagingData -> pagingData.map { it.toDomain() } }

        override fun getMemosByTag(tag: String): Flow<PagingData<Memo>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = AppConfig.PAGE_SIZE,
                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = { dao.getMemosByTag(tag) },
            ).flow
                .map { pagingData -> pagingData.map { it.toDomain() } }

        override fun getAllTags(): Flow<List<String>> = dao.getAllTags().map { entities -> entities.map { it.name } }

        override fun getMemoCount(): Flow<Int> = dao.getMemoCount()

        override fun getAllTimestamps(): Flow<List<Long>> = dao.getAllTimestamps()

        override fun getTagCounts(): Flow<List<com.lomo.domain.model.TagCount>> = dao.getTagCounts()

        override fun getDeletedMemos(): Flow<PagingData<Memo>> =
            Pager(
                config =
                    PagingConfig(
                        pageSize = AppConfig.PAGE_SIZE,
                        prefetchDistance = AppConfig.PREFETCH_DISTANCE,
                        initialLoadSize = AppConfig.INITIAL_LOAD_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = { dao.getDeletedMemos() },
            ).flow
                .map { pagingData -> pagingData.map { it.toDomain() } }

        override suspend fun restoreMemo(memo: Memo) {
            synchronizer.restoreMemo(memo)
            refreshMemos()
        }

        override suspend fun deletePermanently(memo: Memo) {
            synchronizer.deletePermanently(memo)
            refreshMemos()
        }

        override suspend fun saveImage(uri: Uri): String = dataSource.saveImage(uri)

        override suspend fun deleteImage(filename: String) {
            dataSource.deleteImage(filename)
        }

        override suspend fun createVoiceFile(filename: String): Uri = dataSource.createVoiceFile(filename)

        override suspend fun deleteVoiceFile(filename: String) {
            dataSource.deleteVoiceFile(filename)
        }

        override fun getImageUriMap(): Flow<Map<String, String>> =
            imageCacheDao.getAllImages().map { entities ->
                entities.associate { it.filename to it.uriString }
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
                            lastModified = System.currentTimeMillis(),
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

        override fun isShowInputHintsEnabled(): Flow<Boolean> = dataStore.showInputHints

        override suspend fun setShowInputHints(enabled: Boolean) {
            dataStore.updateShowInputHints(enabled)
        }

        override fun isLanSharePairingConfigured(): Flow<Boolean> =
            dataStore.lanSharePairingKeyHex.map { ShareAuthUtils.isValidKeyHex(it) }

        override suspend fun setLanSharePairingCode(pairingCode: String) {
            val keyHex =
                ShareAuthUtils.deriveKeyHexFromPairingCode(pairingCode)
                    ?: throw IllegalArgumentException("Pairing code must be 6-64 characters")
            dataStore.updateLanSharePairingKeyHex(keyHex)
        }

        override suspend fun clearLanSharePairingCode() {
            dataStore.updateLanSharePairingKeyHex(null)
        }

        override fun getStorageFilenameFormat(): Flow<String> = dataStore.storageFilenameFormat

        override suspend fun setStorageFilenameFormat(format: String) {
            dataStore.updateStorageFilenameFormat(format)
        }

        override fun getStorageTimestampFormat(): Flow<String> = dataStore.storageTimestampFormat

        override suspend fun setStorageTimestampFormat(format: String) {
            dataStore.updateStorageTimestampFormat(format)
        }

        override suspend fun createDefaultImageDirectory(): String? =
            try {
                val uri = dataSource.createDirectory("images")
                setImageDirectory(uri)
                uri
            } catch (e: Exception) {
                null
            }

        override suspend fun createDefaultVoiceDirectory(): String? =
            try {
                val uri = dataSource.createDirectory("voice")
                setVoiceDirectory(uri)
                uri
            } catch (e: Exception) {
                null
            }
    }
