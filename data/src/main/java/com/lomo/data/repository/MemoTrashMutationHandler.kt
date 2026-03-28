package com.lomo.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MediaStorageDataSource
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
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val mediaStorageDataSource: MediaStorageDataSource,
        private val memoWriteDao: MemoWriteDao,
        private val memoTagDao: MemoTagDao,
        private val memoFtsDao: MemoFtsDao,
        private val memoTrashDao: MemoTrashDao,
        private val memoSearchDao: MemoSearchDao,
        private val localFileStateDao: LocalFileStateDao,
        private val textProcessor: MemoTextProcessor,
        private val memoVersionJournal: MemoVersionJournal,
    ) {
        suspend fun moveToTrash(memo: Memo) {
            if (!moveToTrashFileOnly(memo)) {
                throw UnsafeWorkspaceMutationException("Unable to move memo to trash safely: ${memo.id}")
            }
            moveToTrashInDb(memo)
            memoVersionJournal.appendLocalRevision(
                memo = memo.copy(isDeleted = true),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.TRASHED,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_TRASH,
            )
        }

        suspend fun moveToTrashInDb(memo: Memo) {
            memoWriteDao.deleteMemoById(memo.id)
            memoTagDao.deleteTagRefsByMemoId(memo.id)
            memoFtsDao.deleteMemoFts(memo.id)
            memoTrashDao.insertTrashMemo(TrashMemoEntity.fromDomain(memo.copy(isDeleted = true)))
        }

        suspend fun moveToTrashFileOnly(memo: Memo): Boolean {
            val filename = memo.dateKey + ".md"
            val cachedUriString = getMainSafUri(filename)
            val currentFileContent = readMainMemoContent(markdownStorageDataSource, cachedUriString, filename)
            val removedBlock =
                currentFileContent?.let { content ->
                    removeMemoBlockFromContent(content, memo, textProcessor)
                }
            return if (removedBlock == null) {
                false
            } else {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    content = removedBlock.blockContent,
                    append = true,
                )

                val remainingContent = removedBlock.remainingLines.joinToString("\n").trim()
                if (remainingContent.isEmpty()) {
                    markdownStorageDataSource.deleteFileIn(
                        MemoDirectoryType.MAIN,
                        filename,
                        cachedUriString.toUriOrNull(),
                    )
                    localFileStateDao.deleteByFilename(filename, false)
                } else {
                    val savedUri =
                        markdownStorageDataSource.saveFileIn(
                            directory = MemoDirectoryType.MAIN,
                            filename = filename,
                            content = removedBlock.remainingLines.joinToString("\n"),
                            append = false,
                            uri = cachedUriString.toUriOrNull(),
                        )
                    markdownStorageDataSource
                        .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                        ?.let { metadata ->
                            upsertMainState(filename, metadata.lastModified, savedUri)
                        }
                }

                markdownStorageDataSource
                    .getFileMetadataIn(MemoDirectoryType.TRASH, filename)
                    ?.let { trashMetadata ->
                        upsertTrashState(filename, trashMetadata.lastModified)
                    }
                true
            }
        }

        suspend fun restoreFromTrash(memo: Memo) {
            if (!restoreFromTrashFileOnly(memo)) {
                throw UnsafeWorkspaceMutationException("Unable to restore trash memo safely: ${memo.id}")
            }
            restoreFromTrashInDb(memo)
            memoVersionJournal.appendLocalRevision(
                memo = memo.copy(isDeleted = false),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_RESTORE,
            )
        }

        suspend fun restoreFromTrashInDb(memo: Memo): Boolean {
            val sourceMemo = memoTrashDao.getTrashMemo(memo.id)?.toDomain()?.copy(isDeleted = false) ?: return false
            persistMainMemo(sourceMemo)
            memoTrashDao.deleteTrashMemoById(sourceMemo.id)
            return true
        }

        suspend fun restoreFromTrashFileOnly(memo: Memo): Boolean {
            val filename = memo.dateKey + ".md"
            val removedBlock =
                markdownStorageDataSource
                    .readFileIn(MemoDirectoryType.TRASH, filename)
                    ?.let { trashContent ->
                        removeMemoBlockFromContent(trashContent, memo, textProcessor)
                    }
            return if (removedBlock == null) {
                Timber.e("restoreMemo: Failed to find memo block in trash file for ${memo.id}")
                false
            } else {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = removedBlock.blockContent,
                    append = true,
                )

                val remainingTrash = removedBlock.remainingLines.joinToString("\n").trim()
                if (remainingTrash.isEmpty()) {
                    markdownStorageDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                    localFileStateDao.deleteByFilename(filename, true)
                } else {
                    markdownStorageDataSource.saveFileIn(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        content = removedBlock.remainingLines.joinToString("\n"),
                        append = false,
                    )
                    markdownStorageDataSource
                        .getFileMetadataIn(MemoDirectoryType.TRASH, filename)
                        ?.let { trashMetadata ->
                            upsertTrashState(filename, trashMetadata.lastModified)
                        }
                }

                markdownStorageDataSource
                    .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                    ?.let { metadata ->
                        upsertMainState(filename, metadata.lastModified)
                    }
                true
            }
        }

        suspend fun deleteFromTrashPermanently(memo: Memo) {
            val filename = memo.dateKey + ".md"
            val removedBlock =
                markdownStorageDataSource
                    .readFileIn(MemoDirectoryType.TRASH, filename)
                    ?.let { trashContent ->
                        removeMemoBlockFromContent(trashContent, memo, textProcessor)
                    }

            if (removedBlock == null) {
                Timber.e("deletePermanently: Failed to find block for ${memo.id}")
                throw UnsafeWorkspaceMutationException("Unable to delete trash memo safely: ${memo.id}")
            } else {
                val remainingContent = removedBlock.remainingLines.joinToString("\n").trim()
                if (remainingContent.isEmpty()) {
                    markdownStorageDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                    localFileStateDao.deleteByFilename(filename, true)
                } else {
                    markdownStorageDataSource.saveFileIn(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        content = removedBlock.remainingLines.joinToString("\n"),
                        append = false,
                    )
                }
                memoTrashDao.deleteTrashMemoById(memo.id)
            }

            memoVersionJournal.appendLocalRevision(
                memo = memo.copy(isDeleted = true),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.DELETED,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_DELETE,
            )
            deleteUnreferencedAttachments(
                memo = memo,
                memoSearchDao = memoSearchDao,
                mediaStorageDataSource = mediaStorageDataSource,
            )
        }

        private suspend fun persistMainMemo(memo: Memo) {
            val entity = MemoEntity.fromDomain(memo)
            memoWriteDao.insertMemo(entity)
            memoTagDao.replaceTagRefsForMemo(entity)
            val tokenized = SearchTokenizer.tokenize(entity.content)
            memoFtsDao.insertMemoFts(MemoFtsEntity(entity.id, tokenized))
        }

        private suspend fun getMainSafUri(filename: String): String? =
            localFileStateDao.getByFilename(filename, false)?.safUri

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

    }

private data class RemovedMemoBlock(
    val remainingLines: MutableList<String>,
    val blockContent: String,
)

private suspend fun readMainMemoContent(
    markdownStorageDataSource: MarkdownStorageDataSource,
    cachedUriString: String?,
    filename: String,
): String? =
    if (cachedUriString != null) {
        markdownStorageDataSource.readFile(cachedUriString.toUri())
            ?: markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
    } else {
        markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, filename)
    }

private fun removeMemoBlockFromContent(
    content: String,
    memo: Memo,
    textProcessor: MemoTextProcessor,
): RemovedMemoBlock? {
    val lines = content.lines().toMutableList()
    val removed =
        textProcessor.removeMemoBlockSafely(
            lines = lines,
            rawContent = memo.rawContent,
            memoId = memo.id,
        )
    return if (removed) {
        RemovedMemoBlock(
            remainingLines = lines,
            blockContent =
                buildString {
                    appendLine()
                    append(memo.rawContent)
                    appendLine()
                },
        )
    } else {
        null
    }
}

private suspend fun deleteUnreferencedAttachments(
    memo: Memo,
    memoSearchDao: MemoSearchDao,
    mediaStorageDataSource: MediaStorageDataSource,
) {
    memo.imageUrls
        .filter(String::isNotBlank)
        .forEach { path ->
            if (memoSearchDao.countMemosAndTrashWithImage(path, memo.id) == 0) {
                if (path.isVoiceFile()) {
                    mediaStorageDataSource.deleteVoiceFile(path)
                } else {
                    mediaStorageDataSource.deleteImage(path)
                }
            }
        }
}

private fun String?.toUriOrNull(): Uri? = this?.let(Uri::parse)

private fun String.isVoiceFile(): Boolean =
    endsWith(".m4a", ignoreCase = true) ||
        endsWith(".mp3", ignoreCase = true) ||
        endsWith(".aac", ignoreCase = true) ||
        startsWith("voice_", ignoreCase = true)
