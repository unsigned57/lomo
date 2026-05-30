package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.MemoRevisionOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MemoRefreshEngine
(
        private val workspaceReader: MemoWorkspaceReader,
        private val localFileStateDao: LocalFileStateDao,
        private val refreshPlanner: MemoRefreshPlanner,
        private val refreshParserWorker: MemoRefreshParserWorker,
        private val refreshDbApplier: MemoRefreshDbApplier,
        private val mutationGate: MemoMutationGate = MemoMutationGate(),
        private val now: () -> Long = { System.currentTimeMillis() },
    ) {
        suspend fun refresh(targetFilename: String? = null) =
            refreshWithOrigin(targetFilename = targetFilename, origin = MemoRevisionOrigin.IMPORT_REFRESH)

        suspend fun refreshImportedSync(targetFilename: String? = null) =
            refreshWithOrigin(targetFilename = targetFilename, origin = MemoRevisionOrigin.IMPORT_SYNC)

        private suspend fun refreshWithOrigin(
            targetFilename: String?,
            origin: MemoRevisionOrigin,
        ) {
            withContext(Dispatchers.IO) {
                runNonFatalCatching {
                    if (targetFilename != null) {
                        mutationGate.withLock {
                            refreshTargetFile(targetFilename, origin)
                        }
                    } else {
                        val refreshStartedAt = now()
                        val mainSyncMetadata = localFileStateDao.getAllByTrashStatus(isTrash = false)
                        val trashSyncMetadata = localFileStateDao.getAllByTrashStatus(isTrash = true)
                        val syncMetadataMap =
                            buildMap(mainSyncMetadata.size + trashSyncMetadata.size) {
                                mainSyncMetadata.forEach { put(it.filename to false, it) }
                                trashSyncMetadata.forEach { put(it.filename to true, it) }
                            }
                        val plan =
                            refreshPlanner.build(
                                syncMetadataMap = syncMetadataMap,
                                mainFilesMetadata = workspaceReader.streamShardMetadata(MemoDirectoryType.MAIN),
                                trashFilesMetadata = workspaceReader.streamShardMetadata(MemoDirectoryType.TRASH),
                                refreshStartedAt = refreshStartedAt,
                            )

                        if (plan.visibleStateResets.isNotEmpty()) {
                            localFileStateDao.upsertAll(plan.visibleStateResets)
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
                        val missingResolution =
                            resolveMissingFiles(
                                syncMetadataMap = syncMetadataMap,
                                confirmedMissingFiles = plan.filesToDeleteInDb,
                                refreshStartedAt = refreshStartedAt,
                            )

                        if (missingResolution.pendingMissingStates.isNotEmpty()) {
                            localFileStateDao.upsertAll(missingResolution.pendingMissingStates)
                        }

                        mutationGate.withLock {
                            refreshDbApplier.apply(
                                parseResult = normalizedParseResult,
                                filesToDeleteInDb = missingResolution.filesToDeleteInDb,
                                origin = origin,
                            )
                        }
                    }
                }.getOrElse { error ->
                    Timber.e(error, "Error during refresh")
                    throw error
                }
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

        private suspend fun refreshTargetFile(
            targetFilename: String,
            origin: MemoRevisionOrigin,
        ) {
            val file = workspaceReader.readShardFileContent(MemoDirectoryType.MAIN, targetFilename)
            if (file == null) {
                refreshDbApplier.apply(
                    parseResult = emptyParseResult(),
                    filesToDeleteInDb = setOf(targetFilename to false),
                    origin = origin,
                )
                return
            }

            val refreshStartedAt = now()
            val syncMetadataMap =
                localFileStateDao.getByFilename(targetFilename, false)?.let { state ->
                    mapOf((targetFilename to false) to state)
                }.orEmpty()
            val normalizedParseResult =
                normalizeMetadata(
                    parseResult = refreshParserWorker.parseMainFileContents(listOf(file)),
                    syncMetadataMap = syncMetadataMap,
                    refreshStartedAt = refreshStartedAt,
                )
            refreshDbApplier.apply(
                parseResult = normalizedParseResult,
                filesToDeleteInDb = emptySet(),
                origin = origin,
            )
        }
    }

private data class MissingFileResolution(
    val pendingMissingStates: List<LocalFileStateEntity>,
    val filesToDeleteInDb: Set<Pair<String, Boolean>>,
)

private fun emptyParseResult() =
    MemoRefreshParseResult(
        mainMemos = emptyList(),
        trashMemos = emptyList(),
        metadataToUpdate = emptyList(),
        mainDatesToReplace = emptySet(),
        trashDatesToReplace = emptySet(),
    )

private const val MISSING_DELETE_CONFIRMATION_COUNT = 2
private const val MISSING_DELETE_CONFIRMATION_WINDOW_MS = 5 * 60_000L
