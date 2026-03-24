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
    ) {
        suspend fun refresh(targetFilename: String? = null) =
            withContext(Dispatchers.IO) {
                runNonFatalCatching {
                    if (targetFilename != null) {
                        refreshTargetFile(targetFilename)
                    } else {
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

                        if (plan.discoveredMainStates.isNotEmpty()) {
                            localFileStateDao.upsertAll(plan.discoveredMainStates)
                        }

                        val parseResult =
                            refreshParserWorker.parse(
                                mainFilesToUpdate = plan.mainFilesToUpdate,
                                trashFilesToUpdate = plan.trashFilesToUpdate,
                            )

                        refreshDbApplier.apply(
                            parseResult = parseResult,
                            filesToDeleteInDb = plan.filesToDeleteInDb,
                        )
                    }
                }.getOrElse { error ->
                    Timber.e(error, "Error during refresh")
                    throw error
                }
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
