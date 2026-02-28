package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileContent
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.MemoDirectoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MemoRefreshEngine
    constructor(
        private val fileDataSource: FileDataSource,
        private val dao: MemoDao,
        private val localFileStateDao: LocalFileStateDao,
        private val parser: MarkdownParser,
        private val refreshPlanner: MemoRefreshPlanner,
        private val refreshParserWorker: MemoRefreshParserWorker,
        private val refreshDbApplier: MemoRefreshDbApplier,
    ) {
        suspend fun refresh(targetFilename: String? = null) =
            withContext(Dispatchers.IO) {
                try {
                    if (targetFilename != null) {
                        refreshTargetFile(targetFilename)
                        return@withContext
                    }

                    val syncMetadataMap =
                        localFileStateDao.getAll().associateBy { it.filename to it.isTrash }
                    val mainFilesMetadata = fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN)
                    val trashFilesMetadata = fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH)

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
                } catch (e: Exception) {
                    Timber.e(e, "Error during refresh")
                    throw e
                }
            }

        private suspend fun refreshTargetFile(targetFilename: String) {
            val files = fileDataSource.listFilesIn(MemoDirectoryType.MAIN, targetFilename)
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
    }
