package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
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
        private val localFileStateDao: LocalFileStateDao,
        private val parser: MarkdownParser,
        private val textProcessor: MemoTextProcessor,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore,
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
                                localFileStateDao.upsert(
                                    LocalFileStateEntity(
                                        filename = targetFilename,
                                        isTrash = false,
                                        lastKnownModifiedTime = files[0].lastModified,
                                        safUri = localFileStateDao.getByFilename(targetFilename, false)?.safUri,
                                    ),
                                )
                            }
                            return@withContext
                        }

                        // Full Incremental Sync with optimized Document ID access
                        val syncMetadataMap =
                            localFileStateDao.getAll().associateBy { it.filename to it.isTrash }
                        // Use optimized methods that return Document IDs
                        val mainFilesMetadata = fileDataSource.listMetadataWithIds()
                        val trashFilesMetadata = fileDataSource.listTrashMetadataWithIds()

                        // Refresh SAF URI cache for main files while preserving sync timestamps.
                        val discoveredMainStates =
                            mainFilesMetadata.mapNotNull { meta ->
                                val key = meta.filename to false
                                val existing = syncMetadataMap[key]
                                val safUri = meta.uriString ?: existing?.safUri
                                if (safUri == null && existing == null) {
                                    null
                                } else {
                                    LocalFileStateEntity(
                                        filename = meta.filename,
                                        isTrash = false,
                                        safUri = safUri,
                                        lastKnownModifiedTime = existing?.lastKnownModifiedTime ?: 0L,
                                    )
                                }
                            }
                        if (discoveredMainStates.isNotEmpty()) {
                            localFileStateDao.upsertAll(discoveredMainStates)
                        }

                        val mainFilesToUpdate =
                            mainFilesMetadata.filter { meta ->
                                val existing = syncMetadataMap[meta.filename to false]
                                existing == null || existing.lastKnownModifiedTime != meta.lastModified
                            }

                        val trashFilesToUpdate =
                            trashFilesMetadata.filter { meta ->
                                val existing = syncMetadataMap[meta.filename to true]
                                existing == null || existing.lastKnownModifiedTime != meta.lastModified
                            }

                        // Parallel parsing with direct Document ID access (faster)
                        // Parallel parsing with batching to prevent OOM
                        val mainMemos = mutableListOf<MemoEntity>()
                        val trashMemos = mutableListOf<TrashMemoEntity>()
                        val metadataToUpdate = mutableListOf<LocalFileStateEntity>()
                        val mainDatesToReplace = mutableSetOf<String>()
                        val trashDatesToReplace = mutableSetOf<String>()

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
                                                val domainMemos =
                                                    parser.parseContent(
                                                        content = content,
                                                        filename = filename,
                                                        fallbackTimestampMillis = meta.lastModified,
                                                    )
                                                domainMemos.map { MemoEntity.fromDomain(it) } to meta
                                            } else {
                                                null
                                            }
                                        }
                                    }.awaitAll()

                            chunkResults.filterNotNull().forEach { (memos, meta) ->
                                val dateStr = meta.filename.removeSuffix(".md")
                                mainDatesToReplace.add(dateStr)
                                mainMemos.addAll(memos)
                                metadataToUpdate.add(
                                    LocalFileStateEntity(
                                        filename = meta.filename,
                                        isTrash = false,
                                        safUri = meta.uriString,
                                        lastKnownModifiedTime = meta.lastModified,
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
                                                val domainMemos =
                                                    parser.parseContent(
                                                        content = content,
                                                        filename = filename,
                                                        fallbackTimestampMillis = meta.lastModified,
                                                    )
                                                domainMemos.map {
                                                    TrashMemoEntity.fromDomain(
                                                        it.copy(isDeleted = true),
                                                    )
                                                } to meta
                                            } else {
                                                null
                                            }
                                        }
                                    }.awaitAll()
                                    .filterNotNull()

                            val trashMemoIdsInChunk =
                                chunkResults
                                    .flatMap { (memos, _) ->
                                        memos.map { it.id }
                                    }.distinct()
                            val activeMemoIdsInDb =
                                if (trashMemoIdsInChunk.isNotEmpty()) {
                                    trashMemoIdsInChunk
                                        .chunked(500)
                                        .flatMap { ids ->
                                            dao.getMemosByIds(ids)
                                        }.asSequence()
                                        .map { it.id }
                                        .toSet()
                                } else {
                                    emptySet()
                                }

                            chunkResults.forEach { (memos, meta) ->
                                val dateStr = meta.filename.removeSuffix(".md")

                                // NEW: Cross-check against DB to prevent Trash overwriting Main
                                // If some of these notes are ALREADY in the DB as non-deleted,
                                // it means we have a duplicate where the note was restored or moved.
                                // The Main version ALWAYS wins.
                                val filteredMemos =
                                    memos.filter { trashMemo ->
                                        trashMemo.id !in activeMemoIdsInDb
                                    }

                                trashDatesToReplace.add(dateStr)
                                trashMemos.addAll(filteredMemos)
                                metadataToUpdate.add(
                                    LocalFileStateEntity(
                                        filename = meta.filename,
                                        isTrash = true,
                                        lastKnownModifiedTime = meta.lastModified,
                                    ),
                                )
                            }
                        }

                        // Identitfy deleted/missing files to remove from DB
                        val currentMainStateKeys = mainFilesMetadata.map { it.filename to false }.toSet()
                        val currentTrashStateKeys = trashFilesMetadata.map { it.filename to true }.toSet()

                        val filesToDeleteInDb =
                            syncMetadataMap.filterKeys { key ->
                                if (key.second) {
                                    key !in currentTrashStateKeys
                                } else {
                                    key !in currentMainStateKeys
                                }
                            }

                        // Batch update database
                        val hasDateReplacements = mainDatesToReplace.isNotEmpty() || trashDatesToReplace.isNotEmpty()
                        if (hasDateReplacements || filesToDeleteInDb.isNotEmpty()) {
                            mainDatesToReplace.forEach { date ->
                                dao.deleteMemosByDate(date)
                            }
                            trashDatesToReplace.forEach { date ->
                                dao.deleteTrashMemosByDate(date)
                            }

                            val deduplicatedMainMemos = mainMemos.associateBy { it.id }.values.toList()
                            val deduplicatedTrashMemos = trashMemos.associateBy { it.id }.values.toList()
                            val mainIds = deduplicatedMainMemos.map { it.id }.toSet()
                            val filteredTrashMemos =
                                deduplicatedTrashMemos.filter { trashMemo ->
                                    trashMemo.id !in mainIds
                                }

                            if (mainIds.isNotEmpty()) {
                                dao.deleteTrashMemosByIds(mainIds.toList())
                            }

                            // Insert/Update active memos from modified main files
                            if (deduplicatedMainMemos.isNotEmpty()) {
                                dao.insertMemos(deduplicatedMainMemos)
                                deduplicatedMainMemos.forEach {
                                    val tokenized =
                                        com.lomo.data.util.SearchTokenizer
                                            .tokenize(it.content)
                                    dao.insertMemoFts(MemoFtsEntity(it.id, tokenized))
                                }
                            }

                            if (filteredTrashMemos.isNotEmpty()) {
                                dao.insertTrashMemos(filteredTrashMemos)
                            }

                            if (metadataToUpdate.isNotEmpty()) {
                                localFileStateDao.upsertAll(metadataToUpdate)
                            }

                            // Handle file deletions
                            filesToDeleteInDb.forEach { (stateKey, _) ->
                                val (filename, isTrash) = stateKey
                                // Find all memos that belong to this file/date and delete them
                                val date = filename.removeSuffix(".md")
                                if (isTrash) {
                                    val trashMemosInDb = dao.getTrashMemosByDate(date)
                                    val trashMemoIds = trashMemosInDb.map { it.id }
                                    if (trashMemoIds.isNotEmpty()) {
                                        dao.deleteTrashMemosByIds(trashMemoIds)
                                    }
                                } else {
                                    val memosInDb = dao.getMemosByDate(date)
                                    val memoIds = memosInDb.map { it.id }
                                    if (memoIds.isNotEmpty()) {
                                        dao.deleteMemosByIds(memoIds)
                                        dao.deleteMemoFtsByIds(memoIds)
                                    }
                                }
                                localFileStateDao.deleteByFilename(filename, isTrash)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error during refresh")
                        throw e
                    } finally {
                        _isSyncing.value = false
                    }
                }
            }

        private suspend fun syncFiles(
            files: List<com.lomo.data.source.FileContent>,
            isTrash: Boolean,
        ) {
            if (isTrash) {
                val allTrashMemos = mutableListOf<TrashMemoEntity>()
                files.forEach { file ->
                    val filename = file.filename.removeSuffix(".md")
                    val domainMemos =
                        parser.parseContent(
                            content = file.content,
                            filename = filename,
                            fallbackTimestampMillis = file.lastModified,
                        )
                    allTrashMemos.addAll(
                        domainMemos.map {
                            TrashMemoEntity.fromDomain(
                                it.copy(isDeleted = true),
                            )
                        },
                    )
                }
                if (allTrashMemos.isNotEmpty()) {
                    dao.insertTrashMemos(allTrashMemos)
                }
            } else {
                val allMemos = mutableListOf<MemoEntity>()
                files.forEach { file ->
                    val filename = file.filename.removeSuffix(".md")
                    val domainMemos =
                        parser.parseContent(
                            content = file.content,
                            filename = filename,
                            fallbackTimestampMillis = file.lastModified,
                        )
                    allMemos.addAll(domainMemos.map { MemoEntity.fromDomain(it) })
                }
                if (allMemos.isNotEmpty()) {
                    dao.insertMemos(allMemos)
                    allMemos.forEach {
                        val tokenized =
                            com.lomo.data.util.SearchTokenizer
                                .tokenize(it.content)
                        dao.insertMemoFts(MemoFtsEntity(it.id, tokenized))
                    }
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
            val dateString = filename.removeSuffix(".md")
            val baseCanonicalTimestamp =
                parser.resolveTimestamp(
                    dateStr = dateString,
                    timeStr = timeString,
                    fallbackTimestampMillis = timestamp,
                )
            val existingFileContent = fileDataSource.readFile(filename).orEmpty()
            val sameTimestampCount = countTimestampOccurrences(existingFileContent, timeString)
            val safeOffset = if (sameTimestampCount > 999) 999 else sameTimestampCount
            val canonicalTimestamp = baseCanonicalTimestamp + safeOffset

            val contentHash =
                content.trim().hashCode().let {
                    kotlin.math.abs(it).toString(16)
                }
            val baseId = "${filename.removeSuffix(".md")}_${timeString}_$contentHash"
            val collisionIndex =
                countBaseIdCollisionsInFile(
                    fileContent = existingFileContent,
                    dateString = dateString,
                    fallbackTimestampMillis = timestamp,
                    baseId = baseId,
                )
            val optimisticId = if (collisionIndex == 0) baseId else "${baseId}_$collisionIndex"

            val rawContent = "- $timeString $content"
            val fileContentToAppend = "\n$rawContent"

            val newMemo =
                Memo(
                    id = optimisticId,
                    content = content,
                    date = dateString,
                    timestamp = canonicalTimestamp,
                    rawContent = rawContent,
                    tags = textProcessor.extractTags(content),
                    imageUrls = textProcessor.extractImages(content),
                    isDeleted = false,
                )

            // File first
            val savedUriString = fileDataSource.saveFile(filename, fileContentToAppend, append = true)
            val metadata = fileDataSource.getFileMetadata(filename)
            if (metadata == null) throw java.io.IOException("Failed to read metadata after save")
            upsertMainState(filename, metadata.lastModified, savedUriString)

            // Then DB
            val entity = MemoEntity.fromDomain(newMemo)
            dao.insertMemo(entity)
            val tokenizedContent =
                com.lomo.data.util.SearchTokenizer
                    .tokenize(entity.content)
            dao.insertMemoFts(MemoFtsEntity(entity.id, tokenizedContent))
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

            // File-first update (write file before DB)
            val cachedUriString = getMainSafUri(filename)
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
                        memoId = memo.id,
                    )

                if (success) {
                    val savedUri = fileDataSource.saveFile(filename, lines.joinToString("\n"), append = false)

                    // Update Sync Metadata
                    val metadata = fileDataSource.getFileMetadata(filename)
                    if (metadata != null) {
                        upsertMainState(filename, metadata.lastModified, savedUri)
                    }

                    // Then DB
                    val finalEntity = MemoEntity.fromDomain(finalUpdatedMemo)
                    dao.insertMemo(finalEntity)
                    val tokenizedContent =
                        com.lomo.data.util.SearchTokenizer
                            .tokenize(finalEntity.content)
                    dao.insertMemoFts(MemoFtsEntity(finalEntity.id, tokenizedContent))
                }
            }
        }

        suspend fun deleteMemo(memo: Memo) = mutex.withLock { deleteMemoInternal(memo) }

        private suspend fun deleteMemoInternal(memo: Memo) {
            // Move to Trash File FIRST (before DB update)
            val filename = memo.date + ".md"
            val cachedUriString = getMainSafUri(filename)
            val currentFileContent =
                if (cachedUriString != null) {
                    fileDataSource.readFile(android.net.Uri.parse(cachedUriString))
                        ?: fileDataSource.readFile(filename)
                } else {
                    fileDataSource.readFile(filename)
                }
            if (currentFileContent == null) return
            val lines = currentFileContent.lines().toMutableList()

            val (start, end) = textProcessor.findMemoBlock(lines, memo.rawContent, memo.timestamp, memo.id)
            if (start != -1 && end >= start) {
                val linesToTrash = lines.subList(start, end + 1)
                // Prep trash content (ensure tidy boundaries)
                val trashContent = "\n" + linesToTrash.joinToString("\n") + "\n"

                // Remove from original file first in memory, then commit to disk
                if (textProcessor.removeMemoBlock(lines, memo.rawContent, memo.timestamp, memo.id)) {
                    // 1. Append to trash file
                    fileDataSource.saveTrashFile(filename, trashContent)

                    // 2. Commit updated Main File
                    // Check if file is now empty (only whitespace remaining)
                    val remainingContent = lines.joinToString("\n").trim()
                    if (remainingContent.isEmpty()) {
                        // Delete the empty date file from main folder
                        val uriToDelete = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                        fileDataSource.deleteFile(filename, uriToDelete)
                        localFileStateDao.deleteByFilename(filename, false)
                    } else {
                        val uriToSave = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                        val savedUri = fileDataSource.saveFile(filename, lines.joinToString("\n"), false, uriToSave)

                        // Update Sync Metadata for Main file
                        val metadata = fileDataSource.getFileMetadata(filename)
                        if (metadata != null) {
                            upsertMainState(filename, metadata.lastModified, savedUri)
                        }
                    }

                    // 3. Update Trash Metadata (The trash file was updated in saveTrashFile above)
                    val trashMetadata = fileDataSource.getTrashFileMetadata(filename)
                    if (trashMetadata != null) {
                        upsertTrashState(filename, trashMetadata.lastModified)
                    }

                    // 4. Update DB after file operations succeed
                    dao.deleteMemoById(memo.id)
                    dao.deleteMemoFts(memo.id)
                    dao.insertTrashMemo(TrashMemoEntity.fromDomain(memo.copy(isDeleted = true)))
                }
            }
        }

        suspend fun restoreMemo(memo: Memo) =
            mutex.withLock {
                val filename = memo.date + ".md"
                val trashContent = fileDataSource.readTrashFile(filename) ?: return@withLock
                val trashLines = trashContent.lines().toMutableList()

                val (start, end) =
                    textProcessor.findMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)
                if (start != -1) {
                    // Remove from Trash FIRST in memory
                    val restoredLines = trashLines.subList(start, end + 1).toList()
                    if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)) {
                        // 1. Append to Main File
                        // Ensure tidy boundaries without excessive padding
                        val restoredBlock = "\n" + restoredLines.joinToString("\n") + "\n"
                        fileDataSource.saveFile(filename, restoredBlock, append = true)

                        // 2. Commit Updated Trash File
                        val remainingTrash = trashLines.joinToString("\n").trim()
                        if (remainingTrash.isEmpty()) {
                            fileDataSource.deleteTrashFile(filename)
                            localFileStateDao.deleteByFilename(filename, true)
                        } else {
                            fileDataSource.saveTrashFile(
                                filename,
                                trashLines.joinToString("\n"),
                                append = false,
                            )
                            // Update Trash Metadata after removal
                            val trashMetadata = fileDataSource.getTrashFileMetadata(filename)
                            if (trashMetadata != null) {
                                upsertTrashState(filename, trashMetadata.lastModified)
                            }
                        }

                        // 3. Update Main Metadata
                        val metadata = fileDataSource.getFileMetadata(filename)
                        if (metadata != null) {
                            upsertMainState(filename, metadata.lastModified)
                        }

                        // Update DB: Set isDeleted = false ONLY IF file operations were successful
                        val entity = MemoEntity.fromDomain(memo.copy(isDeleted = false))
                        dao.insertMemo(entity)
                        dao.deleteTrashMemoById(memo.id)
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

                if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)) {
                    // Check if file is now empty (only whitespace remaining)
                    val remainingContent = trashLines.joinToString("\n").trim()
                    if (remainingContent.isEmpty()) {
                        // Delete the empty date file
                        fileDataSource.deleteTrashFile(filename)
                        localFileStateDao.deleteByFilename(filename, true)
                    } else {
                        fileDataSource.saveTrashFile(
                            filename,
                            trashLines.joinToString("\n"),
                            append = false,
                        )
                    }

                    // Actually remove from DB ONLY IF file operation was successful
                    dao.deleteTrashMemoById(memo.id)
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

        private suspend fun getMainSafUri(filename: String): String? = localFileStateDao.getByFilename(filename, false)?.safUri

        private suspend fun upsertMainState(
            filename: String,
            lastModified: Long,
            safUri: String? = null,
        ) {
            val existing = localFileStateDao.getByFilename(filename, false)
            localFileStateDao.upsert(
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = false,
                    safUri = safUri ?: existing?.safUri,
                    lastKnownModifiedTime = lastModified,
                ),
            )
        }

        private suspend fun upsertTrashState(
            filename: String,
            lastModified: Long,
        ) {
            localFileStateDao.upsert(
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = true,
                    lastKnownModifiedTime = lastModified,
                ),
            )
        }

        private fun isVoiceFile(filename: String): Boolean =
            filename.endsWith(".m4a", ignoreCase = true) ||
                filename.endsWith(".mp3", ignoreCase = true) ||
                filename.endsWith(".aac", ignoreCase = true) ||
                filename.startsWith("voice_", ignoreCase = true)

        private fun countTimestampOccurrences(
            fileContent: String,
            timestamp: String,
        ): Int {
            if (fileContent.isBlank()) return 0
            val pattern = Regex("^\\s*-\\s+${Regex.escape(timestamp)}(?:\\s|$).*")
            return fileContent.lineSequence().count { line ->
                pattern.matches(line)
            }
        }

        private fun countBaseIdCollisionsInFile(
            fileContent: String,
            dateString: String,
            fallbackTimestampMillis: Long,
            baseId: String,
        ): Int {
            if (fileContent.isBlank()) return 0

            val collisionPrefix = "${baseId}_"
            return parser
                .parseContent(
                    content = fileContent,
                    filename = dateString,
                    fallbackTimestampMillis = fallbackTimestampMillis,
                ).count { memo ->
                    memo.id == baseId || memo.id.startsWith(collisionPrefix)
                }
        }
    }
