package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
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
        data class SaveDbResult(
            val savePlan: MemoSavePlan,
            val outboxId: Long,
        )

        suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
            val savePlan = createSavePlan(content, timestamp)
            persistMainMemoEntity(MemoEntity.fromDomain(savePlan.memo))
            flushSavedMemoToFile(savePlan)
        }

        suspend fun saveMemoInDb(
            content: String,
            timestamp: Long,
        ): SaveDbResult {
            val savePlan = createSavePlan(content, timestamp)
            val outboxId =
                dao.persistMemoWithOutbox(
                    memo = MemoEntity.fromDomain(savePlan.memo),
                    outbox = buildCreateOutbox(savePlan),
                )
            return SaveDbResult(savePlan = savePlan, outboxId = outboxId)
        }

        suspend fun flushSavedMemoToFile(savePlan: MemoSavePlan) {
            val cachedUriString = getMainSafUri(savePlan.filename)
            val cachedUri = cachedUriString.toPersistedUriOrNull()
            val savedUriString =
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = savePlan.filename,
                    content = "\n${savePlan.rawContent}",
                    append = true,
                    uri = cachedUri,
                )
            upsertMainState(savePlan.filename, System.currentTimeMillis(), savedUriString)
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

            if (!flushMemoUpdateToFile(memo, newContent)) return
            val finalUpdatedMemo = buildUpdatedMemo(memo, newContent)
            persistMainMemoEntity(MemoEntity.fromDomain(finalUpdatedMemo))
        }

        suspend fun updateMemoInDb(
            memo: Memo,
            newContent: String,
        ): Long? {
            val sourceMemo = dao.getMemo(memo.id)?.toDomain() ?: return null

            if (newContent.isBlank()) {
                return dao.moveMemoToTrashWithOutbox(
                    trashMemo = TrashMemoEntity.fromDomain(sourceMemo.copy(isDeleted = true)),
                    outbox = buildDeleteOutbox(sourceMemo),
                )
            }

            val finalUpdatedMemo = buildUpdatedMemo(sourceMemo, newContent)
            return dao.persistMemoWithOutbox(
                memo = MemoEntity.fromDomain(finalUpdatedMemo),
                outbox = buildUpdateOutbox(sourceMemo, newContent),
            )
        }

        suspend fun flushMemoUpdateToFile(
            memo: Memo,
            newContent: String,
        ): Boolean {
            if (newContent.isBlank()) {
                return trashMutationHandler.moveToTrashFileOnly(memo)
            }

            val timeString = formatMemoTime(memo.timestamp)
            val filename = memo.date + ".md"
            val cachedUriString = getMainSafUri(filename)
            val cachedUri = cachedUriString.toPersistedUriOrNull()
            val currentFileContent =
                if (cachedUri != null) {
                    fileDataSource.readFile(cachedUri)
                        ?: fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                } else {
                    fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                }

            if (currentFileContent == null) return false
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
            if (!success) return false

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
            return true
        }

        suspend fun deleteMemo(memo: Memo) {
            trashMutationHandler.moveToTrash(memo)
        }

        suspend fun deleteMemoInDb(memo: Memo): Long? {
            val sourceMemo = dao.getMemo(memo.id)?.toDomain() ?: return null
            return dao.moveMemoToTrashWithOutbox(
                trashMemo = TrashMemoEntity.fromDomain(sourceMemo.copy(isDeleted = true)),
                outbox = buildDeleteOutbox(sourceMemo),
            )
        }

        suspend fun flushDeleteMemoToFile(memo: Memo): Boolean = trashMutationHandler.moveToTrashFileOnly(memo)

        suspend fun restoreMemo(memo: Memo) {
            trashMutationHandler.restoreFromTrash(memo)
        }

        suspend fun deletePermanently(memo: Memo) {
            trashMutationHandler.deleteFromTrashPermanently(memo)
        }

        suspend fun hasPendingMemoFileOutbox(): Boolean = dao.getMemoFileOutboxCount() > 0

        suspend fun nextMemoFileOutbox(): MemoFileOutboxEntity? = dao.getMemoFileOutboxBatch(limit = 1).firstOrNull()

        suspend fun acknowledgeMemoFileOutbox(id: Long) {
            dao.deleteMemoFileOutboxById(id)
        }

        suspend fun markMemoFileOutboxFailed(
            id: Long,
            throwable: Throwable?,
        ) {
            dao.markMemoFileOutboxFailed(
                id = id,
                updatedAt = System.currentTimeMillis(),
                lastError = throwable?.message?.take(512),
            )
        }

        suspend fun flushMemoFileOutbox(item: MemoFileOutboxEntity): Boolean =
            when (item.operation) {
                MemoFileOutboxOp.CREATE -> flushCreateFromOutbox(item)
                MemoFileOutboxOp.UPDATE -> {
                    val newContent = item.newContent ?: return false
                    flushMemoUpdateToFile(outboxSourceMemo(item), newContent)
                }

                MemoFileOutboxOp.DELETE -> flushDeleteMemoToFile(outboxSourceMemo(item))
                else -> false
            }

        private suspend fun persistMainMemoEntity(entity: MemoEntity) {
            dao.insertMemo(entity)
            dao.replaceTagRefsForMemo(entity)
            val tokenizedContent = SearchTokenizer.tokenize(entity.content)
            dao.insertMemoFts(MemoFtsEntity(entity.id, tokenizedContent))
        }

        private suspend fun createSavePlan(
            content: String,
            timestamp: Long,
        ): MemoSavePlan {
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
            return savePlanFactory.create(
                content = content,
                timestamp = timestamp,
                filenameFormat = filenameFormat,
                timestampFormat = timestampFormat,
                existingFileContent = "",
                precomputedSameTimestampCount = precomputedSameTimestampCount,
                precomputedCollisionCount = precomputedCollisionCount,
            )
        }

        private fun buildCreateOutbox(savePlan: MemoSavePlan): MemoFileOutboxEntity =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.CREATE,
                memoId = savePlan.memo.id,
                memoDate = savePlan.memo.date,
                memoTimestamp = savePlan.memo.timestamp,
                memoRawContent = savePlan.memo.rawContent,
                newContent = savePlan.memo.content,
                createRawContent = savePlan.rawContent,
            )

        private fun buildUpdateOutbox(
            sourceMemo: Memo,
            newContent: String,
        ): MemoFileOutboxEntity =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.UPDATE,
                memoId = sourceMemo.id,
                memoDate = sourceMemo.date,
                memoTimestamp = sourceMemo.timestamp,
                memoRawContent = sourceMemo.rawContent,
                newContent = newContent,
                createRawContent = null,
            )

        private fun buildDeleteOutbox(sourceMemo: Memo): MemoFileOutboxEntity =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.DELETE,
                memoId = sourceMemo.id,
                memoDate = sourceMemo.date,
                memoTimestamp = sourceMemo.timestamp,
                memoRawContent = sourceMemo.rawContent,
                newContent = null,
                createRawContent = null,
            )

        private fun outboxSourceMemo(item: MemoFileOutboxEntity): Memo =
            Memo(
                id = item.memoId,
                timestamp = item.memoTimestamp,
                content = item.newContent.orEmpty(),
                rawContent = item.memoRawContent,
                date = item.memoDate,
            )

        private suspend fun flushCreateFromOutbox(item: MemoFileOutboxEntity): Boolean {
            val createRawContent = item.createRawContent ?: return false
            val filename = item.memoDate + ".md"
            val cachedUriString = getMainSafUri(filename)
            val cachedUri = cachedUriString.toPersistedUriOrNull()
            val savedUriString =
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = "\n$createRawContent",
                    append = true,
                    uri = cachedUri,
                )
            upsertMainState(filename, System.currentTimeMillis(), savedUriString)
            return true
        }

        private suspend fun buildUpdatedMemo(
            memo: Memo,
            newContent: String,
        ): Memo {
            val timeString = formatMemoTime(memo.timestamp)
            val updatedMemo =
                memo.copy(
                    content = newContent,
                    rawContent = newContent,
                    timestamp = memo.timestamp,
                    tags = textProcessor.extractTags(newContent),
                    imageUrls = textProcessor.extractImages(newContent),
                )
            return updatedMemo.copy(rawContent = "- $timeString $newContent")
        }

        private suspend fun formatMemoTime(timestamp: Long): String {
            val timestampFormat = dataStore.storageTimestampFormat.first()
            return DateTimeFormatter
                .ofPattern(timestampFormat)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp))
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
