package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.FileSyncDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.FileSyncEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.MemoTagCrossRef
import com.lomo.data.local.entity.TagEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class MemoSynchronizer
    @Inject
    constructor(
        private val fileDataSource: FileDataSource,
        private val dao: MemoDao,
        private val fileSyncDao: FileSyncDao,
        private val parser: MarkdownParser,
        private val textProcessor: MemoTextProcessor,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore,
        private val fileCacheDao: com.lomo.data.local.dao.FileCacheDao,
    ) {
        private val mutex = Mutex()

        // Sync state for UI observation - helps prevent writes during active sync
        private val _isSyncing = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isSyncing: kotlinx.coroutines.flow.StateFlow<Boolean> = _isSyncing

        suspend fun refresh(targetFilename: String? = null) =
            mutex.withLock {
                _isSyncing.value = true
                withContext(Dispatchers.IO) {
                    try {
                        if (targetFilename != null) {
                            // Target sync (keep existing logic but update metadata)
                            val files = fileDataSource.listFiles(targetFilename)
                            if (files.isNotEmpty()) {
                                syncFiles(files, isTrash = false)
                                fileSyncDao.insertSyncMetadata(
                                    FileSyncEntity(
                                        targetFilename,
                                        files[0].lastModified,
                                        isTrash = false,
                                    ),
                                )
                            }
                            return@withContext
                        }

                        // Full Incremental Sync with optimized Document ID access
                        val syncMetadataMap =
                            fileSyncDao.getAllSyncMetadata().associateBy {
                                it.filename to it.isTrash
                            }
                        // Use optimized methods that return Document IDs
                        val mainFilesMetadata = fileDataSource.listMetadataWithIds()
                        val trashFilesMetadata = fileDataSource.listTrashMetadataWithIds()

                        // Populate File Cache
                        val cacheEntities =
                            mainFilesMetadata.mapNotNull {
                                it.uriString?.let { uri ->
                                    com.lomo.data.local.entity.FileCacheEntity(
                                        it.filename,
                                        uri,
                                        it.lastModified,
                                    )
                                }
                            }
                        if (cacheEntities.isNotEmpty()) {
                            fileCacheDao.insertAll(cacheEntities)
                        }

                        val mainFilesToUpdate =
                            mainFilesMetadata.filter { meta ->
                                val existing = syncMetadataMap[meta.filename to false]
                                existing == null || existing.lastModified != meta.lastModified
                            }

                        val trashFilesToUpdate =
                            trashFilesMetadata.filter { meta ->
                                val existing = syncMetadataMap[meta.filename to true]
                                existing == null || existing.lastModified != meta.lastModified
                            }

                        // Parallel parsing with direct Document ID access (faster)
                        // Parallel parsing with batching to prevent OOM
                        val allMemos = mutableListOf<MemoEntity>()
                        val metadataToUpdate = mutableListOf<FileSyncEntity>()

                        // Process Main files in chunks
                        mainFilesToUpdate.chunked(10).forEach { chunk ->
                            val chunkResults =
                                chunk
                                    .map { meta ->
                                        async(Dispatchers.Default) {
                                            // Use Document ID for direct access (skips findFile traversal)
                                            val content = fileDataSource.readFileByDocumentId(meta.documentId)
                                            if (content != null) {
                                                val filename = meta.filename.removeSuffix(".md")
                                                val domainMemos = parser.parseContent(content, filename)
                                                domainMemos.map {
                                                    MemoEntity.fromDomain(
                                                        it.copy(isDeleted = false),
                                                    )
                                                } to meta
                                            } else {
                                                null
                                            }
                                        }
                                    }.awaitAll()

                            chunkResults.filterNotNull().forEach { (memos, meta) ->
                                val dateStr = meta.filename.removeSuffix(".md")
                                dao.deleteMemosByDate(dateStr, isDeleted = false)

                                allMemos.addAll(memos)
                                metadataToUpdate.add(
                                    FileSyncEntity(
                                        meta.filename,
                                        meta.lastModified,
                                        isTrash = false,
                                    ),
                                )
                            }
                        }

                        // Process Trash files in chunks
                        trashFilesToUpdate.chunked(10).forEach { chunk ->
                            val chunkResults =
                                chunk
                                    .map { meta ->
                                        async(Dispatchers.Default) {
                                            val content =
                                                fileDataSource.readTrashFileByDocumentId(meta.documentId)
                                            if (content != null) {
                                                val filename = meta.filename.removeSuffix(".md")
                                                val domainMemos = parser.parseContent(content, filename)
                                                domainMemos.map {
                                                    MemoEntity.fromDomain(
                                                        it.copy(isDeleted = true),
                                                    )
                                                } to meta
                                            } else {
                                                null
                                            }
                                        }
                                    }.awaitAll()

                            chunkResults.filterNotNull().forEach { (memos, meta) ->
                                val dateStr = meta.filename.removeSuffix(".md")

                                // NEW: Cross-check against DB to prevent Trash overwriting Main
                                // If some of these notes are ALREADY in the DB as non-deleted,
                                // it means we have a duplicate where the note was restored or moved.
                                // The Main version ALWAYS wins.
                                val filteredMemos =
                                    memos.filter { trashMemo ->
                                        // Optimization: This usually only happens during a 'Move' collision
                                        val inDb = dao.getMemo(trashMemo.id)
                                        inDb == null || inDb.isDeleted
                                    }

                                dao.deleteMemosByDate(dateStr, isDeleted = true)
                                allMemos.addAll(filteredMemos)
                                metadataToUpdate.add(
                                    FileSyncEntity(meta.filename, meta.lastModified, isTrash = true),
                                )
                            }
                        }

                        // Identitfy deleted/missing files to remove from DB
                        val currentMainFilenames = mainFilesMetadata.map { it.filename }.toSet()
                        val currentTrashFilenames = trashFilesMetadata.map { it.filename }.toSet()

                        val filesToDeleteInDb =
                            syncMetadataMap.filter { (key, _) ->
                                val (filename, isTrash) = key
                                if (isTrash) {
                                    filename !in currentTrashFilenames
                                } else {
                                    filename !in currentMainFilenames
                                }
                            }

                        // Batch update database
                        if (allMemos.isNotEmpty() || filesToDeleteInDb.isNotEmpty()) {
                            // Deduplicate allMemos: If the same ID exists as both deleted and non-deleted,
                            // prioritize the non-deleted one (Main file wins).
                            val deduplicatedMemos =
                                allMemos
                                    .groupBy { it.id }
                                    .mapValues { (id, list) ->
                                        list.find { !it.isDeleted } ?: list.first()
                                    }.values
                                    .toList()

                            // Insert/Update memos from modified files
                            if (deduplicatedMemos.isNotEmpty()) {
                                // Important: Before inserting main memos, ensure we delete any existing "deleted"
                                // records for these IDs to prevent duplication/conflict in some edge cases.
                                val mainIds = deduplicatedMemos.filter { !it.isDeleted }.map { it.id }
                                if (mainIds.isNotEmpty()) {
                                    // This force-cleans any stale trash records in DB
                                    mainIds.forEach { id ->
                                        dao.deleteMemoById(id)
                                    }
                                }

                                dao.insertMemos(deduplicatedMemos)
                                deduplicatedMemos.forEach {
                                    updateMemoTags(it)
                                    val tokenized =
                                        com.lomo.data.util.SearchTokenizer
                                            .tokenize(it.content)
                                    dao.insertMemoFts(MemoFtsEntity(it.id, tokenized))
                                }
                                fileSyncDao.insertSyncMetadata(metadataToUpdate)
                            }

                            // Handle file deletions
                            filesToDeleteInDb.forEach { (key, _) ->
                                val (filename, isTrash) = key
                                // Find all memos that belong to this file/date and delete them
                                val date = filename.removeSuffix(".md")
                                val memosInDb = dao.getMemosByDate(date, isTrash)
                                memosInDb.forEach {
                                    dao.deleteMemo(it)
                                    dao.deleteMemoFts(it.id)
                                }
                                fileSyncDao.deleteSyncMetadata(filename, isTrash)
                            }

                            // Cleanup orphan tags after full refresh to ensure sidebar consistency
                            dao.deleteOrphanTags()
                        }
                    } catch (e: Exception) {
                        Timber.e("MemoSynchronizer", "Error during refresh", e)
                    } finally {
                        _isSyncing.value = false
                    }
                }
            }

        private suspend fun syncFiles(
            files: List<com.lomo.data.source.FileContent>,
            isTrash: Boolean,
        ) {
            val allMemos = mutableListOf<MemoEntity>()
            files.forEach { file ->
                val filename = file.filename.removeSuffix(".md")
                val domainMemos = parser.parseContent(file.content, filename)
                allMemos.addAll(domainMemos.map { MemoEntity.fromDomain(it.copy(isDeleted = isTrash)) })
            }
            if (allMemos.isNotEmpty()) {
                dao.insertMemos(allMemos)
                allMemos.forEach {
                    updateMemoTags(it)
                    val tokenized =
                        com.lomo.data.util.SearchTokenizer
                            .tokenize(it.content)
                    dao.insertMemoFts(MemoFtsEntity(it.id, tokenized))
                }
            }
        }

        suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) = mutex.withLock {
            // Read formats from DataStore
            val filenameFormat = dataStore.storageFilenameFormat.first()
            val timestampFormat = dataStore.storageTimestampFormat.first()

            val instant = Instant.ofEpochMilli(timestamp)
            val zoneId = ZoneId.systemDefault()
            val filename =
                DateTimeFormatter
                    .ofPattern(filenameFormat)
                    .withZone(zoneId)
                    .format(instant) + ".md"
            val timeString =
                DateTimeFormatter
                    .ofPattern(timestampFormat)
                    .withZone(zoneId)
                    .format(instant)

            val contentHash = content.trim().hashCode().let {
                kotlin.math.abs(it).toString(16)
            }
            var baseId = "${filename.removeSuffix(".md")}_${timeString}_$contentHash"
            var optimisticId = baseId
            var collisionCount = 1

            // Check if ID exists in DB (even if deleted, to avoid primary key conflict if we re-used it?)
            // Actually, if it's deleted, we could theoretically reuse it?
            // But for consistency with Parser logic (which counts items), we should simply finding the next available slot.
            // But Parser counts items *in file*. DB might have items *in trash*.
            // If I have 1 item in file (ID_0). And ID_1 is in Trash.
            // If I add new item, Parser will see 2 items? No, Parser only parses File.
            // Parser will read file, find 2 items -> ID_0, ID_1.
            // Save logic adds to File.
            // So we need to ensure the ID we pick here matches what Parser WILL generate next time it reads the file.
            // If file has N items with this timestamp, we should pick ID_N.
            // But we can't easily count file items without reading file.
            // Strategy: Check DB for existence of ID. If exists (active or deleted), increment.
            while (dao.getMemo(optimisticId) != null) {
                optimisticId = "${baseId}_$collisionCount"
                collisionCount++
            }
            
            val rawContent = "- $timeString $content"
            val fileContentToAppend = "\n$rawContent"

            val newMemo =
                Memo(
                    id = optimisticId,
                    content = content,
                    date = filename.removeSuffix(".md"),
                    timestamp = timestamp,
                    rawContent = rawContent,
                    tags = textProcessor.extractTags(content),
                    imageUrls = textProcessor.extractImages(content),
                    isDeleted = false,
                )

            // DB
            val entity = MemoEntity.fromDomain(newMemo)
            dao.insertMemo(entity)
            updateMemoTags(entity)
            val tokenizedContent =
                com.lomo.data.util.SearchTokenizer
                    .tokenize(entity.content)
            dao.insertMemoFts(MemoFtsEntity(entity.id, tokenizedContent))

            // File
            val savedUriString =
                fileDataSource.saveFile(filename, fileContentToAppend, append = true)

            // Update Sync Metadata explicitly to prevent re-import
            val metadata = fileDataSource.getFileMetadata(filename)
            if (metadata != null) {
                fileSyncDao.insertSyncMetadata(
                    FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                )
                if (savedUriString != null) {
                    fileCacheDao.insert(
                        com.lomo.data.local.entity.FileCacheEntity(
                            filename,
                            savedUriString,
                            metadata.lastModified,
                        ),
                    )
                }
            }
        }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) = mutex.withLock {
            if (newContent.isBlank()) {
                deleteMemoInternal(memo)
                return@withLock
            }

            if (dao.getMemo(memo.id) == null) return@withLock

            // Read format for updates
            val timestampFormat = dataStore.storageTimestampFormat.first()

            val filename = memo.date + ".md"
            val updatedMemo =
                memo.copy(
                    content = newContent,
                    rawContent = newContent,
                    timestamp = memo.timestamp,
                    tags = textProcessor.extractTags(newContent),
                    imageUrls = textProcessor.extractImages(newContent),
                )

            val timeString =
                DateTimeFormatter
                    .ofPattern(timestampFormat)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(memo.timestamp))
            val newRawContentFull = "- $timeString $newContent"
            val finalUpdatedMemo = updatedMemo.copy(rawContent = newRawContentFull)

            val finalEntity = MemoEntity.fromDomain(finalUpdatedMemo)
            dao.insertMemo(finalEntity)
            updateMemoTags(finalEntity)
            val tokenizedContent =
                com.lomo.data.util.SearchTokenizer
                    .tokenize(finalEntity.content)
            dao.insertMemoFts(MemoFtsEntity(finalEntity.id, tokenizedContent))

            // File
            val cachedUriString = fileCacheDao.getFileUri(filename)?.uriString
            val currentFileContent =
                if (cachedUriString != null) {
                    fileDataSource.readFile(android.net.Uri.parse(cachedUriString))
                        ?: fileDataSource.readFile(filename)
                } else {
                    fileDataSource.readFile(filename)
                }

            if (currentFileContent != null) {
                val lines = currentFileContent.lines().toMutableList()
                val success =
                    textProcessor.replaceMemoBlock(
                        lines,
                        memo.rawContent,
                        memo.timestamp,
                        newContent,
                        timeString, // Pass generated timestamp string
                    )

                if (success) {
                    val savedUri =
                        fileDataSource.saveFile(filename, lines.joinToString("\n"), append = false)

                    // Update Sync Metadata
                    val metadata = fileDataSource.getFileMetadata(filename)
                    if (metadata != null) {
                        fileSyncDao.insertSyncMetadata(
                            FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                        )
                        if (savedUri != null) {
                            fileCacheDao.insert(
                                com.lomo.data.local.entity.FileCacheEntity(
                                    filename,
                                    savedUri,
                                    metadata.lastModified,
                                ),
                            )
                        }
                    }
                }
            }
        }

        suspend fun deleteMemo(memo: Memo) = mutex.withLock { deleteMemoInternal(memo) }

        private suspend fun deleteMemoInternal(memo: Memo) {
            // Move to Trash File FIRST (before DB update)
            val filename = memo.date + ".md"
            val cachedUriString = fileCacheDao.getFileUri(filename)?.uriString
            val currentFileContent =
                if (cachedUriString != null) {
                    fileDataSource.readFile(android.net.Uri.parse(cachedUriString))
                        ?: fileDataSource.readFile(filename)
                } else {
                    fileDataSource.readFile(filename)
                }
            if (currentFileContent == null) return
            val lines = currentFileContent.lines().toMutableList()

            val (start, end) = textProcessor.findMemoBlock(lines, memo.rawContent, memo.timestamp)
            if (start != -1 && end >= start) {
                val linesToTrash = lines.subList(start, end + 1)
                // Prep trash content (ensure tidy boundaries)
                val trashContent = "\n" + linesToTrash.joinToString("\n") + "\n"

                // Remove from original file first in memory, then commit to disk
                if (textProcessor.removeMemoBlock(lines, memo.rawContent, memo.timestamp)) {
                    // 1. Append to trash file
                    fileDataSource.saveTrashFile(filename, trashContent)

                    // 2. Commit updated Main File
                    // Check if file is now empty (only whitespace remaining)
                    val remainingContent = lines.joinToString("\n").trim()
                    if (remainingContent.isEmpty()) {
                        // Delete the empty date file from main folder
                        val uriToDelete = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                        fileDataSource.deleteFile(filename, uriToDelete)
                        fileSyncDao.deleteSyncMetadata(filename, isTrash = false)
                    } else {
                        val uriToSave = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                        fileDataSource.saveFile(filename, lines.joinToString("\n"), false, uriToSave)

                        // Update Sync Metadata for Main file
                        val metadata = fileDataSource.getFileMetadata(filename)
                        if (metadata != null) {
                            fileSyncDao.insertSyncMetadata(
                                FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                            )
                        }
                    }

                    // 3. Update Trash Metadata (The trash file was updated in saveTrashFile above)
                    val trashMetadata = fileDataSource.getTrashFileMetadata(filename)
                    if (trashMetadata != null) {
                        fileSyncDao.insertSyncMetadata(
                            FileSyncEntity(filename, trashMetadata.lastModified, isTrash = true),
                        )
                    }

                    // 4. Update DB after file operations succeed
                    dao.softDeleteMemo(memo.id)
                    dao.deleteMemoFts(memo.id)

                    // Cleanup orphan tags
                    dao.deleteOrphanTags()
                }
            }
        }

        suspend fun restoreMemo(memo: Memo) =
            mutex.withLock {
                val filename = memo.date + ".md"
                val trashContent = fileDataSource.readTrashFile(filename) ?: return@withLock
                val trashLines = trashContent.lines().toMutableList()

                val (start, end) =
                    textProcessor.findMemoBlock(trashLines, memo.rawContent, memo.timestamp)
                if (start != -1) {
                    // Remove from Trash FIRST in memory
                    val restoredLines = trashLines.subList(start, end + 1).toList()
                    if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp)) {
                        // 1. Append to Main File
                        // Ensure tidy boundaries without excessive padding
                        val restoredBlock = "\n" + restoredLines.joinToString("\n") + "\n"
                        fileDataSource.saveFile(filename, restoredBlock, append = true)

                        // 2. Commit Updated Trash File
                        val remainingTrash = trashLines.joinToString("\n").trim()
                        if (remainingTrash.isEmpty()) {
                            fileDataSource.deleteTrashFile(filename)
                            fileSyncDao.deleteSyncMetadata(filename, isTrash = true)
                        } else {
                            fileDataSource.saveTrashFile(
                                filename,
                                trashLines.joinToString("\n"),
                                append = false,
                            )
                            // Update Trash Metadata after removal
                            val trashMetadata = fileDataSource.getTrashFileMetadata(filename)
                            if (trashMetadata != null) {
                                fileSyncDao.insertSyncMetadata(
                                    FileSyncEntity(filename, trashMetadata.lastModified, isTrash = true),
                                )
                            }
                        }

                        // 3. Update Main Metadata
                        val metadata = fileDataSource.getFileMetadata(filename)
                        if (metadata != null) {
                            fileSyncDao.insertSyncMetadata(
                                FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                            )
                        }

                        // Update DB: Set isDeleted = false ONLY IF file operations were successful
                        val entity = MemoEntity.fromDomain(memo.copy(isDeleted = false))
                        dao.insertMemo(entity)
                        updateMemoTags(entity)
                        val tokenized =
                            com.lomo.data.util.SearchTokenizer
                                .tokenize(entity.content)
                        dao.insertMemoFts(MemoFtsEntity(entity.id, tokenized))
                    } else {
                        Timber.e("restoreMemo: Failed to find memo block in trash file for ${memo.id}")
                        // Fallback: If removal failed, maybe the note is already gone from file but not DB
                        // We set it to false in DB anyway to let user see it, but it's dangerous
                    }
                }
            }

        suspend fun deletePermanently(memo: Memo) =
            mutex.withLock {
                // Delete from Trash File (DB removal moved inside success block)

                // Delete from Trash File
                val filename = memo.date + ".md"
                val trashContent = fileDataSource.readTrashFile(filename) ?: return@withLock
                val trashLines = trashContent.lines().toMutableList()

                if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp)) {
                    // Check if file is now empty (only whitespace remaining)
                    val remainingContent = trashLines.joinToString("\n").trim()
                    if (remainingContent.isEmpty()) {
                        // Delete the empty date file
                        fileDataSource.deleteTrashFile(filename)
                        fileSyncDao.deleteSyncMetadata(filename, isTrash = true)
                    } else {
                        fileDataSource.saveTrashFile(
                            filename,
                            trashLines.joinToString("\n"),
                            append = false,
                        )
                    }

                    // Actually remove from DB ONLY IF file operation was successful
                    dao.deleteMemo(MemoEntity.fromDomain(memo))
                    dao.deleteMemoFts(memo.id)
                } else {
                    Timber.e("deletePermanently: Failed to find block for ${memo.id}")
                }

                // Orphan Image/Voice Cleanup (Issue #3)
                // Check if any images/voice files in this memo are NOT used by other memos
                if (memo.imageUrls.isNotEmpty()) {
                    memo.imageUrls.forEach { path ->
                        if (path.isNotBlank()) {
                            // Verify if anyone else uses this file
                            val count = dao.countMemosWithImage(path, memo.id)
                            if (count == 0) {
                                // Safe to delete
                                if (isVoiceFile(path)) {
                                    fileDataSource.deleteVoiceFile(path)
                                } else {
                                    fileDataSource.deleteImage(path)
                                }
                            }
                        }
                    }
                }
            }

        private fun isVoiceFile(filename: String): Boolean =
            filename.endsWith(".m4a", ignoreCase = true) ||
                filename.endsWith(".mp3", ignoreCase = true) ||
                filename.endsWith(".aac", ignoreCase = true) ||
                filename.startsWith("voice_", ignoreCase = true)

        private suspend fun updateMemoTags(memo: MemoEntity) {
            val tags =
                if (memo.tags.isNotEmpty()) {
                    memo.tags.split(",").filter { it.isNotBlank() }
                } else {
                    emptyList()
                }

            // Always clear old tags for this memo to ensure consistency (handle removals)
            dao.deleteMemoTags(memo.id)

            if (tags.isNotEmpty()) {
                // Ensure tags exist in Tag table
                val tagEntities = tags.map { TagEntity(it) }
                dao.insertTags(tagEntities)

                // Link them
                val refs = tags.map { MemoTagCrossRef(memo.id, it) }
                dao.insertMemoTagCrossRefs(refs)
            }
        }
    }
