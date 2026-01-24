package com.lomo.data.repository

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
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore, // Injected
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
                        val allMemos = mutableListOf<MemoEntity>()
                        val metadataToUpdate = mutableListOf<FileSyncEntity>()

                        val mainResults =
                            mainFilesToUpdate
                                .map { meta ->
                                    async(Dispatchers.Default) {
                                        // Use Document ID for direct access (skips findFile traversal)
                                        val content = fileDataSource.readFileByDocumentId(meta.documentId)
                                        if (content != null) {
                                            val filename = meta.filename.removeSuffix(".md")
                                            val domainMemos =
                                                parser.parseContent(content, filename)
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

                        mainResults.filterNotNull().forEach { (memos, meta) ->
                            allMemos.addAll(memos)
                            metadataToUpdate.add(
                                FileSyncEntity(
                                    meta.filename,
                                    meta.lastModified,
                                    isTrash = false,
                                ),
                            )
                        }

                        val trashResults =
                            trashFilesToUpdate
                                .map { meta ->
                                    async(Dispatchers.Default) {
                                        // Use Document ID for direct access
                                        val content =
                                            fileDataSource.readTrashFileByDocumentId(meta.documentId)
                                        if (content != null) {
                                            val filename = meta.filename.removeSuffix(".md")
                                            val domainMemos =
                                                parser.parseContent(content, filename)
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

                        trashResults.filterNotNull().forEach { (memos, meta) ->
                            allMemos.addAll(memos)
                            metadataToUpdate.add(
                                FileSyncEntity(meta.filename, meta.lastModified, isTrash = true),
                            )
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
                            // If we have updates, we might need a more sophisticated merge logic
                            // For now, let's update modified files and clear deleted ones

                            // Insert/Update memos from modified files
                            if (allMemos.isNotEmpty()) {
                                dao.insertMemos(allMemos)
                                allMemos.forEach {
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
                        }
                    } catch (e: Exception) {
                        timber.log.Timber.e("MemoSynchronizer", "Error during refresh", e)
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

            // Added max retry limit to prevent theoretical infinite loop
            // Ensure unique timestamp to prevent ID collision
            var safeTimestamp = timestamp
            var retryCount = 0
            val maxRetries = 60 // Max 60 retries = 1 minute window

            while (retryCount < maxRetries) {
                val instant = Instant.ofEpochMilli(safeTimestamp)
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
                val potentialId = "${filename.removeSuffix(".md")}_$timeString"

                if (dao.getMemo(potentialId) == null) {
                    break
                }
                // Collision detected, add 1 second (since ID resolution is 1 second)
                safeTimestamp += 1000
                retryCount++
            }

            if (retryCount >= maxRetries) {
                timber.log.Timber.e("saveMemo: Failed to generate unique ID after $maxRetries retries")
                throw IllegalStateException("Unable to generate unique memo ID after $maxRetries attempts")
            }

            val instant = Instant.ofEpochMilli(safeTimestamp)
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

            val rawContent = "- $timeString $content"
            val fileContentToAppend = "\n$rawContent"
            val optimisticId = "${filename.removeSuffix(".md")}_$timeString"

            val newMemo =
                Memo(
                    id = optimisticId,
                    content = content,
                    date = filename.removeSuffix(".md"),
                    timestamp = safeTimestamp,
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
            fileDataSource.saveFile(filename, fileContentToAppend, append = true)

            // Update Sync Metadata explicitly to prevent re-import
            val metadata = fileDataSource.getFileMetadata(filename)
            if (metadata != null) {
                fileSyncDao.insertSyncMetadata(
                    FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                )
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
            val currentFileContent = fileDataSource.readFile(filename)
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
                    fileDataSource.saveFile(filename, lines.joinToString("\n"), append = false)

                    // Update Sync Metadata
                    val metadata = fileDataSource.getFileMetadata(filename)
                    if (metadata != null) {
                        fileSyncDao.insertSyncMetadata(
                            FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                        )
                    }
                }
            }
        }

        suspend fun deleteMemo(memo: Memo) = mutex.withLock { deleteMemoInternal(memo) }

        private suspend fun deleteMemoInternal(memo: Memo) {
            // Move to Trash File FIRST (before DB update)
            val filename = memo.date + ".md"
            val currentFileContent = fileDataSource.readFile(filename) ?: return
            val lines = currentFileContent.lines().toMutableList()

            val (start, end) = textProcessor.findMemoBlock(lines, memo.rawContent, memo.timestamp)
            if (start != -1 && end >= start) {
                val linesToTrash = lines.subList(start, end + 1)
                val trashContent = "\n" + linesToTrash.joinToString("\n")

                // Save to trash first
                fileDataSource.saveTrashFile(filename, trashContent)

                // Update Trash Metadata
                val trashMetadata = fileDataSource.getFileMetadata(filename) // Wait, this gets main file meta.
                // We need to check trash file existence/metadata actually.
                // But getFileMetadata(filename) checks root file.
                // For trash we need a way to check trash file metadata?
                // StorageBackend has listTrashMetadata, but not getTrashFileMetadata.
                // However, syncFiles (which is called by refresh) iterates listTrashMetadata.
                // So we should try to update it if possible, but listTrashMetadata scans dir.
                // Let's assume trash sync is less critical for the "reappearing" bug which happens in Main list.
                // The reappearing bug is about the MAIN file being re-read.

                // Remove from original file
                if (textProcessor.removeMemoBlock(lines, memo.rawContent, memo.timestamp)) {
                    // Check if file is now empty (only whitespace remaining)
                    val remainingContent = lines.joinToString("\n").trim()
                    if (remainingContent.isEmpty()) {
                        // Delete the empty date file from main folder
                        fileDataSource.deleteFile(filename)
                        fileSyncDao.deleteSyncMetadata(filename, isTrash = false)
                    } else {
                        fileDataSource.saveFile(filename, lines.joinToString("\n"), false)

                        // Update Sync Metadata for Main file
                        val metadata = fileDataSource.getFileMetadata(filename)
                        if (metadata != null) {
                            fileSyncDao.insertSyncMetadata(
                                FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                            )
                        }
                    }

                    // Only update DB after file operations succeed
                    dao.softDeleteMemo(memo.id)
                    dao.deleteMemoFts(memo.id)

                    // Cleanup orphan tags (tags with no remaining non-deleted memos)
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
                    val restoredLines = trashLines.subList(start, end + 1)
                    val restoredBlock = "\n" + restoredLines.joinToString("\n")

                    // Append to Main
                    fileDataSource.saveFile(filename, restoredBlock, append = true)

                    // Update Main Metadata
                    val metadata = fileDataSource.getFileMetadata(filename)
                    if (metadata != null) {
                        fileSyncDao.insertSyncMetadata(
                            FileSyncEntity(filename, metadata.lastModified, isTrash = false),
                        )
                    }

                    // Remove from Trash
                    val removeSuccess =
                        textProcessor.removeMemoBlock(
                            trashLines,
                            memo.rawContent,
                            memo.timestamp,
                        )
                    if (removeSuccess) {
                        fileDataSource.saveTrashFile(
                            filename,
                            trashLines.joinToString("\n"),
                            append = false,
                        )
                    }

                    // Update DB: Set isDeleted = false
                    val entity = MemoEntity.fromDomain(memo.copy(isDeleted = false))
                    dao.insertMemo(entity)
                    updateMemoTags(entity)
                    val tokenized =
                        com.lomo.data.util.SearchTokenizer
                            .tokenize(entity.content)
                    dao.insertMemoFts(MemoFtsEntity(entity.id, tokenized))
                }
            }

        suspend fun deletePermanently(memo: Memo) =
            mutex.withLock {
                // Delete from DB
                dao.deleteMemo(MemoEntity.fromDomain(memo))
                dao.deleteMemoFts(memo.id)

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
