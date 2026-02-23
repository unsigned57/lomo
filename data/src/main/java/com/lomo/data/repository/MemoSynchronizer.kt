package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import com.lomo.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class MemoSynchronizer
    @Inject
    constructor(
        private val fileDataSource: FileDataSource,
        private val dao: MemoDao,
        private val localFileStateDao: LocalFileStateDao,
        private val parser: MarkdownParser,
        private val mutationHandler: MemoMutationHandler,
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

                                // If some note IDs are already active in DB, Main version wins.
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

                        // Identify deleted/missing files to remove from DB
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
                                val memoIds = dao.getMemosByDate(date).map { it.id }
                                if (memoIds.isNotEmpty()) {
                                    dao.deleteTagRefsByMemoIds(memoIds)
                                }
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
                                dao.replaceTagRefsForMemos(deduplicatedMainMemos)
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
                                        dao.deleteTagRefsByMemoIds(memoIds)
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
                    dao.replaceTagRefsForMemos(allMemos)
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
        ) = mutex.withLock { mutationHandler.saveMemo(content, timestamp) }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) = mutex.withLock { mutationHandler.updateMemo(memo, newContent) }

        suspend fun deleteMemo(memo: Memo) = mutex.withLock { mutationHandler.deleteMemo(memo) }

        suspend fun restoreMemo(memo: Memo) = mutex.withLock { mutationHandler.restoreMemo(memo) }

        suspend fun deletePermanently(memo: Memo) = mutex.withLock { mutationHandler.deletePermanently(memo) }
    }
