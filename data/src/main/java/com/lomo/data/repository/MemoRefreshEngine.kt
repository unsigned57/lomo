package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileContent
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.runNonFatalCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MemoRefreshEngine
(
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val memoWriteDao: MemoWriteDao,
        private val memoTagDao: MemoTagDao,
        private val memoFtsDao: MemoFtsDao,
        private val memoTrashDao: MemoTrashDao,
        private val localFileStateDao: LocalFileStateDao,
        private val parser: MarkdownParser,
        private val refreshPlanner: MemoRefreshPlanner,
        private val refreshParserWorker: MemoRefreshParserWorker,
        private val refreshDbApplier: MemoRefreshDbApplier,
        private val now: () -> Long = { System.currentTimeMillis() },
    ) {
        suspend fun refresh(targetFilename: String? = null) =
            withContext(Dispatchers.IO) {
                runNonFatalCatching {
                    if (targetFilename != null) {
                        refreshTargetFile(targetFilename)
                    } else {
                        val refreshStartedAt = now()
                        val syncMetadataMap =
                            localFileStateDao.getAll().associateBy { it.filename to it.isTrash }
                        val mainFilesMetadata =
                            markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN)
                        val trashFilesMetadata =
                            markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH)

                        val plan =
                            refreshPlanner.build(
                                syncMetadataMap = syncMetadataMap,
                                mainFilesMetadata = mainFilesMetadata,
                                trashFilesMetadata = trashFilesMetadata,
                            )

                        val visibleStateResets =
                            buildVisibleStateResets(
                                syncMetadataMap = syncMetadataMap,
                                mainFilesMetadata = mainFilesMetadata,
                                trashFilesMetadata = trashFilesMetadata,
                                refreshStartedAt = refreshStartedAt,
                            )
                        if (visibleStateResets.isNotEmpty()) {
                            localFileStateDao.upsertAll(visibleStateResets)
                        }

                        val parseResult =
                            refreshParserWorker.parse(
                                mainFilesToUpdate = plan.mainFilesToUpdate,
                                trashFilesToUpdate = plan.trashFilesToUpdate,
                            )
                        val normalizedParseResult =
                            normalizeMetadata(
                                parseResult = parseResult,
                                syncMetadataMap = syncMetadataMap,
                                refreshStartedAt = refreshStartedAt,
                            )
                        val confirmedMissingFiles = confirmMissingFiles(plan.filesToDeleteInDb)
                        val missingResolution =
                            resolveMissingFiles(
                                syncMetadataMap = syncMetadataMap,
                                confirmedMissingFiles = confirmedMissingFiles,
                                refreshStartedAt = refreshStartedAt,
                            )

                        if (missingResolution.pendingMissingStates.isNotEmpty()) {
                            localFileStateDao.upsertAll(missingResolution.pendingMissingStates)
                        }

                        refreshDbApplier.apply(
                            parseResult = normalizedParseResult,
                            filesToDeleteInDb = missingResolution.filesToDeleteInDb,
                        )
                    }
                }.getOrElse { error ->
                    Timber.e(error, "Error during refresh")
                    throw error
                }
            }

        private suspend fun confirmMissingFiles(
            candidates: Set<Pair<String, Boolean>>,
        ): Set<Pair<String, Boolean>> =
            buildSet {
                candidates.forEach { candidate ->
                    if (isConfirmedMissing(candidate.first, candidate.second)) {
                        add(candidate)
                    }
                }
            }

        private suspend fun isConfirmedMissing(
            filename: String,
            isTrash: Boolean,
        ): Boolean {
            val directory = if (isTrash) MemoDirectoryType.TRASH else MemoDirectoryType.MAIN
            return runNonFatalCatching {
                markdownStorageDataSource.getFileMetadataIn(directory, filename) == null
            }.onFailure { error ->
                Timber.w(error, "Skip deleting %s because existence check failed", filename)
            }.getOrDefault(false)
        }

        private fun buildVisibleStateResets(
            syncMetadataMap: Map<Pair<String, Boolean>, LocalFileStateEntity>,
            mainFilesMetadata: List<com.lomo.data.source.FileMetadataWithId>,
            trashFilesMetadata: List<com.lomo.data.source.FileMetadataWithId>,
            refreshStartedAt: Long,
        ): List<LocalFileStateEntity> {
            val visibleStates =
                buildList {
                    mainFilesMetadata.forEach { add(it to false) }
                    trashFilesMetadata.forEach { add(it to true) }
                }
            return visibleStates.mapNotNull { (metadata, isTrash) ->
                val existing = syncMetadataMap[metadata.filename to isTrash] ?: return@mapNotNull null
                existing.copy(
                    safUri = if (isTrash) existing.safUri else metadata.uriString ?: existing.safUri,
                    missingSince = null,
                    missingCount = 0,
                    lastSeenAt = refreshStartedAt,
                )
            }
        }

        private fun normalizeMetadata(
            parseResult: MemoRefreshParseResult,
            syncMetadataMap: Map<Pair<String, Boolean>, LocalFileStateEntity>,
            refreshStartedAt: Long,
        ): MemoRefreshParseResult =
            parseResult.copy(
                metadataToUpdate =
                    parseResult.metadataToUpdate.map { metadata ->
                        val existing = syncMetadataMap[metadata.filename to metadata.isTrash]
                        metadata.copy(
                            safUri = metadata.safUri ?: existing?.safUri,
                            missingSince = null,
                            missingCount = 0,
                            lastSeenAt = refreshStartedAt,
                        )
                    },
            )

        private fun resolveMissingFiles(
            syncMetadataMap: Map<Pair<String, Boolean>, LocalFileStateEntity>,
            confirmedMissingFiles: Set<Pair<String, Boolean>>,
            refreshStartedAt: Long,
        ): MissingFileResolution {
            val pendingMissingStates = mutableListOf<LocalFileStateEntity>()
            val filesToDeleteInDb = mutableSetOf<Pair<String, Boolean>>()

            confirmedMissingFiles.forEach { key ->
                val existing = syncMetadataMap[key]
                if (existing == null) {
                    filesToDeleteInDb += key
                    return@forEach
                }

                val missingSince = existing.missingSince ?: refreshStartedAt
                val missingCount = existing.missingCount + 1
                val exceedsCountThreshold = missingCount >= MISSING_DELETE_CONFIRMATION_COUNT
                val exceedsTimeThreshold =
                    refreshStartedAt - missingSince >= MISSING_DELETE_CONFIRMATION_WINDOW_MS

                if (exceedsCountThreshold || exceedsTimeThreshold) {
                    filesToDeleteInDb += key
                } else {
                    pendingMissingStates +=
                        existing.copy(
                            missingSince = missingSince,
                            missingCount = missingCount,
                        )
                }
            }

            return MissingFileResolution(
                pendingMissingStates = pendingMissingStates,
                filesToDeleteInDb = filesToDeleteInDb,
            )
        }

        private suspend fun refreshTargetFile(targetFilename: String) {
            val files = markdownStorageDataSource.listFilesIn(MemoDirectoryType.MAIN, targetFilename)
            if (files.isEmpty()) return

            syncFiles(files, isTrash = false)
            localFileStateDao.upsert(
                LocalFileStateEntity(
                    filename = targetFilename,
                    isTrash = false,
                    lastKnownModifiedTime = files[0].lastModified,
                    safUri = localFileStateDao.getByFilename(targetFilename, false)?.safUri,
                    lastSeenAt = now(),
                ),
            )
        }

        private suspend fun syncFiles(
            files: List<FileContent>,
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
                    memoTrashDao.insertTrashMemos(allTrashMemos)
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
                    memoWriteDao.insertMemos(allMemos)
                    memoTagDao.replaceTagRefsForMemos(allMemos)
                    memoFtsDao.replaceMemoFtsBatch(
                        allMemos.map {
                            MemoFtsEntity(
                                memoId = it.id,
                                content =
                                    com.lomo.data.util.SearchTokenizer
                                        .tokenize(it.content),
                            )
                        },
                    )
                }
            }
        }
    }

private data class MissingFileResolution(
    val pendingMissingStates: List<LocalFileStateEntity>,
    val filesToDeleteInDb: Set<Pair<String, Boolean>>,
)

private const val MISSING_DELETE_CONFIRMATION_COUNT = 2
private const val MISSING_DELETE_CONFIRMATION_WINDOW_MS = 5 * 60_000L
