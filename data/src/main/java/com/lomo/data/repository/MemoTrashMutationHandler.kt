package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.Memo
import timber.log.Timber
import javax.inject.Inject

/**
 * Encapsulates memo -> trash lifecycle mutations.
 */
class MemoTrashMutationHandler
    @Inject
    constructor(
        private val fileDataSource: FileDataSource,
        private val dao: MemoDao,
        private val localFileStateDao: LocalFileStateDao,
        private val textProcessor: MemoTextProcessor,
    ) {
        suspend fun moveToTrash(memo: Memo) {
            if (!moveToTrashFileOnly(memo)) return
            moveToTrashInDb(memo)
        }

        suspend fun moveToTrashInDb(memo: Memo) {
            dao.deleteMemoById(memo.id)
            dao.deleteTagRefsByMemoId(memo.id)
            dao.deleteMemoFts(memo.id)
            dao.insertTrashMemo(TrashMemoEntity.fromDomain(memo.copy(isDeleted = true)))
        }

        suspend fun moveToTrashFileOnly(memo: Memo): Boolean {
            val filename = memo.dateKey + ".md"
            val cachedUriString = getMainSafUri(filename)
            val currentFileContent =
                if (cachedUriString != null) {
                    fileDataSource.readFile(Uri.parse(cachedUriString))
                        ?: fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                } else {
                    fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
                }
            if (currentFileContent == null) return false
            val lines = currentFileContent.lines().toMutableList()

            val (start, end) = textProcessor.findMemoBlock(lines, memo.rawContent, memo.timestamp, memo.id)
            if (start == -1 || end < start) return false

            val linesToTrash = lines.subList(start, end + 1)
            val trashContent = "\n" + linesToTrash.joinToString("\n") + "\n"

            if (!textProcessor.removeMemoBlock(lines, memo.rawContent, memo.timestamp, memo.id)) return false

            fileDataSource.saveFileIn(
                directory = MemoDirectoryType.TRASH,
                filename = filename,
                content = trashContent,
                append = true,
            )

            val remainingContent = lines.joinToString("\n").trim()
            if (remainingContent.isEmpty()) {
                val uriToDelete = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                fileDataSource.deleteFileIn(MemoDirectoryType.MAIN, filename, uriToDelete)
                localFileStateDao.deleteByFilename(filename, false)
            } else {
                val uriToSave = if (cachedUriString != null) Uri.parse(cachedUriString) else null
                val savedUri =
                    fileDataSource.saveFileIn(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        content = lines.joinToString("\n"),
                        append = false,
                        uri = uriToSave,
                    )

                val metadata = fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                if (metadata != null) {
                    upsertMainState(filename, metadata.lastModified, savedUri)
                }
            }

            val trashMetadata = fileDataSource.getFileMetadataIn(MemoDirectoryType.TRASH, filename)
            if (trashMetadata != null) {
                upsertTrashState(filename, trashMetadata.lastModified)
            }
            return true
        }

        suspend fun restoreFromTrash(memo: Memo) {
            if (!restoreFromTrashFileOnly(memo)) return
            restoreFromTrashInDb(memo)
        }

        suspend fun restoreFromTrashInDb(memo: Memo): Boolean {
            val sourceMemo = dao.getTrashMemo(memo.id)?.toDomain()?.copy(isDeleted = false) ?: return false
            persistMainMemo(sourceMemo)
            dao.deleteTrashMemoById(sourceMemo.id)
            return true
        }

        suspend fun restoreFromTrashFileOnly(memo: Memo): Boolean {
            val filename = memo.dateKey + ".md"
            val trashContent = fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename) ?: return false
            val trashLines = trashContent.lines().toMutableList()

            val (start, end) =
                textProcessor.findMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)
            if (start == -1) return false

            val restoredLines = trashLines.subList(start, end + 1).toList()
            if (!textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)) {
                Timber.e("restoreMemo: Failed to find memo block in trash file for ${memo.id}")
                return false
            }

            val restoredBlock = "\n" + restoredLines.joinToString("\n") + "\n"
            fileDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = filename,
                content = restoredBlock,
                append = true,
            )

            val remainingTrash = trashLines.joinToString("\n").trim()
            if (remainingTrash.isEmpty()) {
                fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                localFileStateDao.deleteByFilename(filename, true)
            } else {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = trashLines.joinToString("\n"),
                    append = false,
                )
                val trashMetadata = fileDataSource.getFileMetadataIn(MemoDirectoryType.TRASH, filename)
                if (trashMetadata != null) {
                    upsertTrashState(filename, trashMetadata.lastModified)
                }
            }

            val metadata = fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            if (metadata != null) {
                upsertMainState(filename, metadata.lastModified)
            }
            return true
        }

        suspend fun deleteFromTrashPermanently(memo: Memo) {
            val filename = memo.dateKey + ".md"
            val trashContent = fileDataSource.readFileIn(MemoDirectoryType.TRASH, filename) ?: return
            val trashLines = trashContent.lines().toMutableList()

            if (textProcessor.removeMemoBlock(trashLines, memo.rawContent, memo.timestamp, memo.id)) {
                val remainingContent = trashLines.joinToString("\n").trim()
                if (remainingContent.isEmpty()) {
                    fileDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                    localFileStateDao.deleteByFilename(filename, true)
                } else {
                    fileDataSource.saveFileIn(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        content = trashLines.joinToString("\n"),
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
                        val count = dao.countMemosAndTrashWithImage(path, memo.id)
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

        private suspend fun persistMainMemo(memo: Memo) {
            val entity = MemoEntity.fromDomain(memo)
            dao.insertMemo(entity)
            dao.replaceTagRefsForMemo(entity)
            val tokenized = SearchTokenizer.tokenize(entity.content)
            dao.insertMemoFts(MemoFtsEntity(entity.id, tokenized))
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
    }
