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
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Handles memo mutation workflow (file-first + db update).
 * Keeps file editing and CRUD out of [MemoSynchronizer] to avoid god-object growth.
 */
class MemoMutationHandler
    @Inject
    constructor(
        private val fileDataSource: FileDataSource,
        private val dao: MemoDao,
        private val localFileStateDao: LocalFileStateDao,
        private val parser: MarkdownParser,
        private val textProcessor: MemoTextProcessor,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore,
    ) {
        suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
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

            val savedUriString = fileDataSource.saveFile(filename, fileContentToAppend, append = true)
            val metadata = fileDataSource.getFileMetadata(filename)
            if (metadata == null) throw java.io.IOException("Failed to read metadata after save")
            upsertMainState(filename, metadata.lastModified, savedUriString)

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
        ) {
            if (newContent.isBlank()) {
                deleteMemoInternal(memo)
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
            val newRawContentFull = "- $timeString $newContent"
            val finalUpdatedMemo = updatedMemo.copy(rawContent = newRawContentFull)

            val cachedUriString = getMainSafUri(filename)
            val currentFileContent =
                if (cachedUriString != null) {
                    fileDataSource.readFile(Uri.parse(cachedUriString))
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
                        timeString,
                        memoId = memo.id,
                    )

                if (success) {
                    val savedUri = fileDataSource.saveFile(filename, lines.joinToString("\n"), append = false)

                    val metadata = fileDataSource.getFileMetadata(filename)
                    if (metadata != null) {
                        upsertMainState(filename, metadata.lastModified, savedUri)
                    }

                    val finalEntity = MemoEntity.fromDomain(finalUpdatedMemo)
                    dao.insertMemo(finalEntity)
                    val tokenizedContent =
                        com.lomo.data.util.SearchTokenizer
                            .tokenize(finalEntity.content)
                    dao.insertMemoFts(MemoFtsEntity(finalEntity.id, tokenizedContent))
                }
            }
        }

        suspend fun deleteMemo(memo: Memo) {
            deleteMemoInternal(memo)
        }

        suspend fun restoreMemo(memo: Memo) {
            val filename = memo.date + ".md"
            val trashContent = fileDataSource.readTrashFile(filename) ?: return
            val trashLines = trashContent.lines().toMutableList()

            val (start, end) =
                textProcessor.findMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)
            if (start != -1) {
                val restoredLines = trashLines.subList(start, end + 1).toList()
                if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)) {
                    val restoredBlock = "\n" + restoredLines.joinToString("\n") + "\n"
                    fileDataSource.saveFile(filename, restoredBlock, append = true)

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
                        val trashMetadata = fileDataSource.getTrashFileMetadata(filename)
                        if (trashMetadata != null) {
                            upsertTrashState(filename, trashMetadata.lastModified)
                        }
                    }

                    val metadata = fileDataSource.getFileMetadata(filename)
                    if (metadata != null) {
                        upsertMainState(filename, metadata.lastModified)
                    }

                    val entity = MemoEntity.fromDomain(memo.copy(isDeleted = false))
                    dao.insertMemo(entity)
                    dao.deleteTrashMemoById(memo.id)
                    val tokenized =
                        com.lomo.data.util.SearchTokenizer
                            .tokenize(entity.content)
                    dao.insertMemoFts(MemoFtsEntity(entity.id, tokenized))
                } else {
                    Timber.e("restoreMemo: Failed to find memo block in trash file for ${memo.id}")
                }
            }
        }

        suspend fun deletePermanently(memo: Memo) {
            val filename = memo.date + ".md"
            val trashContent = fileDataSource.readTrashFile(filename) ?: return
            val trashLines = trashContent.lines().toMutableList()

            if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)) {
                val remainingContent = trashLines.joinToString("\n").trim()
                if (remainingContent.isEmpty()) {
                    fileDataSource.deleteTrashFile(filename)
                    localFileStateDao.deleteByFilename(filename, true)
                } else {
                    fileDataSource.saveTrashFile(
                        filename,
                        trashLines.joinToString("\n"),
                        append = false,
                    )
                }

                dao.deleteTrashMemoById(memo.id)
            } else {
                Timber.e("deletePermanently: Failed to find block for ${memo.id}")
            }

            if (memo.imageUrls.isNotEmpty()) {
                memo.imageUrls.forEach { path ->
                    if (path.isNotBlank()) {
                        val count = dao.countMemosWithImage(path, memo.id)
                        if (count == 0) {
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

        private suspend fun deleteMemoInternal(memo: Memo) {
            val filename = memo.date + ".md"
            val cachedUriString = getMainSafUri(filename)
            val currentFileContent =
                if (cachedUriString != null) {
                    fileDataSource.readFile(Uri.parse(cachedUriString))
                        ?: fileDataSource.readFile(filename)
                } else {
                    fileDataSource.readFile(filename)
                }
            if (currentFileContent == null) return
            val lines = currentFileContent.lines().toMutableList()

            val (start, end) = textProcessor.findMemoBlock(lines, memo.rawContent, memo.timestamp, memo.id)
            if (start != -1 && end >= start) {
                val linesToTrash = lines.subList(start, end + 1)
                val trashContent = "\n" + linesToTrash.joinToString("\n") + "\n"

                if (textProcessor.removeMemoBlock(lines, memo.rawContent, memo.timestamp, memo.id)) {
                    fileDataSource.saveTrashFile(filename, trashContent)

                    val remainingContent = lines.joinToString("\n").trim()
                    if (remainingContent.isEmpty()) {
                        val uriToDelete = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                        fileDataSource.deleteFile(filename, uriToDelete)
                        localFileStateDao.deleteByFilename(filename, false)
                    } else {
                        val uriToSave = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                        val savedUri = fileDataSource.saveFile(filename, lines.joinToString("\n"), false, uriToSave)

                        val metadata = fileDataSource.getFileMetadata(filename)
                        if (metadata != null) {
                            upsertMainState(filename, metadata.lastModified, savedUri)
                        }
                    }

                    val trashMetadata = fileDataSource.getTrashFileMetadata(filename)
                    if (trashMetadata != null) {
                        upsertTrashState(filename, trashMetadata.lastModified)
                    }

                    dao.deleteMemoById(memo.id)
                    dao.deleteMemoFts(memo.id)
                    dao.insertTrashMemo(TrashMemoEntity.fromDomain(memo.copy(isDeleted = true)))
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
