package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.Memo
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Handles active memo mutations (save/update). Trash lifecycle is delegated to [MemoTrashMutationHandler].
 */
class MemoMutationHandler
    @Inject
    constructor(
        private val fileDataSource: FileDataSource,
        private val dao: MemoDao,
        private val localFileStateDao: LocalFileStateDao,
        private val savePlanFactory: MemoSavePlanFactory,
        private val textProcessor: MemoTextProcessor,
        private val dataStore: LomoDataStore,
        private val trashMutationHandler: MemoTrashMutationHandler,
    ) {
        suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
            val filenameFormat = dataStore.storageFilenameFormat.first()
            val timestampFormat = dataStore.storageTimestampFormat.first()
            val zoneId = ZoneId.systemDefault()
            val instant = Instant.ofEpochMilli(timestamp)
            val dateString =
                DateTimeFormatter
                    .ofPattern(filenameFormat)
                    .withZone(zoneId)
                    .format(instant)
            val timeString =
                DateTimeFormatter
                    .ofPattern(timestampFormat)
                    .withZone(zoneId)
                    .format(instant)
            val contentHash = abs(content.trim().hashCode()).toString(16)
            val baseId = "${dateString}_${timeString}_$contentHash"
            val precomputedCollisionCount =
                dao.countMemoIdCollisions(
                    baseId = baseId,
                    globPattern = "${baseId}_*",
                )
            val precomputedSameTimestampCount = dao.countMemosByIdGlob("${dateString}_${timeString}_*")
            val candidateFilename = "$dateString.md"
            val cachedUriString = getMainSafUri(candidateFilename)
            val cachedUri = cachedUriString.toPersistedUriOrNull()
            val savePlan =
                savePlanFactory.create(
                    content = content,
                    timestamp = timestamp,
                    filenameFormat = filenameFormat,
                    timestampFormat = timestampFormat,
                    existingFileContent = "",
                    precomputedSameTimestampCount = precomputedSameTimestampCount,
                    precomputedCollisionCount = precomputedCollisionCount,
                )

            val savedUriString =
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = savePlan.filename,
                    content = "\n${savePlan.rawContent}",
                    append = true,
                    uri = cachedUri,
                )
            upsertMainState(savePlan.filename, System.currentTimeMillis(), savedUriString)

            persistMainMemoEntity(MemoEntity.fromDomain(savePlan.memo))
        }

        suspend fun prewarmTodayMemoTarget(timestamp: Long) {
            val filenameFormat = dataStore.storageFilenameFormat.first()
            val dateString =
                DateTimeFormatter
                    .ofPattern(filenameFormat)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(timestamp))
            val filename = "$dateString.md"
            val existingState = localFileStateDao.getByFilename(filename, false)
            if (existingState?.safUri.toPersistedUriOrNull() != null) return

            val savedUriString =
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = "",
                    append = true,
                    uri = null,
                )
            upsertMainState(
                filename = filename,
                lastModified = System.currentTimeMillis(),
                safUri = savedUriString ?: existingState?.safUri,
            )
        }

        suspend fun cleanupTodayPrewarmedMemoTarget(timestamp: Long) {
            val filenameFormat = dataStore.storageFilenameFormat.first()
            val dateString =
                DateTimeFormatter
                    .ofPattern(filenameFormat)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(timestamp))
            if (dao.countMemosByIdGlob("${dateString}_*") > 0) return

            val filename = "$dateString.md"
            val cachedUriString = getMainSafUri(filename)
            val cachedUri = cachedUriString.toPersistedUriOrNull()
            val fileContent =
                if (cachedUri != null) {
                    fileDataSource.readFile(cachedUri)
                        ?: fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                } else {
                    fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                }

            when {
                fileContent == null -> {
                    localFileStateDao.deleteByFilename(filename, false)
                }

                fileContent.isBlank() -> {
                    fileDataSource.deleteFileIn(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        uri = cachedUri,
                    )
                    localFileStateDao.deleteByFilename(filename, false)
                }
            }
        }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            if (newContent.isBlank()) {
                trashMutationHandler.moveToTrash(memo)
                return
            }
            if (dao.getMemo(memo.id) == null) return

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
            val finalUpdatedMemo = updatedMemo.copy(rawContent = "- $timeString $newContent")

            val cachedUriString = getMainSafUri(filename)
            val cachedUri = cachedUriString.toPersistedUriOrNull()
            val currentFileContent =
                if (cachedUri != null) {
                    fileDataSource.readFile(cachedUri)
                        ?: fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                } else {
                    fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                }

            if (currentFileContent != null) {
                val lines = currentFileContent.lines().toMutableList()
                val success =
                    textProcessor.replaceMemoBlock(
                        lines,
                        memo.rawContent,
                        memo.timestamp,
                        newContent,
                        timeString,
                        memo.id,
                    )
                if (success) {
                    val savedUri =
                        fileDataSource.saveFileIn(
                            directory = MemoDirectoryType.MAIN,
                            filename = filename,
                            content = lines.joinToString("\n"),
                            append = false,
                        )
                    val metadata = fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                    if (metadata != null) {
                        upsertMainState(filename, metadata.lastModified, savedUri)
                    }
                    persistMainMemoEntity(MemoEntity.fromDomain(finalUpdatedMemo))
                }
            }
        }

        suspend fun deleteMemo(memo: Memo) {
            trashMutationHandler.moveToTrash(memo)
        }

        suspend fun restoreMemo(memo: Memo) {
            trashMutationHandler.restoreFromTrash(memo)
        }

        suspend fun deletePermanently(memo: Memo) {
            trashMutationHandler.deleteFromTrashPermanently(memo)
        }

        private suspend fun persistMainMemoEntity(entity: MemoEntity) {
            dao.insertMemo(entity)
            dao.replaceTagRefsForMemo(entity)
            val tokenizedContent = SearchTokenizer.tokenize(entity.content)
            dao.insertMemoFts(MemoFtsEntity(entity.id, tokenizedContent))
        }

        private suspend fun getMainSafUri(filename: String): String? = localFileStateDao.getByFilename(filename, false)?.safUri

        private fun String?.toPersistedUriOrNull(): Uri? {
            val value = this ?: return null
            if (!(value.startsWith("content://") || value.startsWith("file://"))) return null
            return Uri.parse(value)
        }

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
    }
