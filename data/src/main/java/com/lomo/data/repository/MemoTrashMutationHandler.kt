package com.lomo.data.repository

import android.net.Uri
import androidx.core.net.toUri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.IndexedTextLines
import com.lomo.data.util.MemoTextProcessor
import com.lomo.data.util.findDestructiveMemoBlock
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
        private val memoImageDao: MemoImageDao,
        private val memoTrashDao: MemoTrashDao,
        private val memoSearchDao: MemoSearchDao,
        private val localFileStateDao: LocalFileStateDao,
        private val textProcessor: MemoTextProcessor,
        private val memoVersionRecorder: AsyncMemoVersionRecorder,
    ) {
        suspend fun moveToTrash(memo: Memo) {
            if (!moveToTrashFileOnly(memo)) {
                throw UnsafeWorkspaceMutationException("Unable to move memo to trash safely: ${memo.id}")
            }
            moveToTrashInDb(memo)
            memoVersionRecorder.enqueueLocalRevision(
                memo = memo.copy(isDeleted = true),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.TRASHED,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_TRASH,
            )
        }

        suspend fun moveToTrashInDb(memo: Memo) {
            memoWriteDao.deleteMemoById(memo.id)
            memoTagDao.deleteTagRefsByMemoId(memo.id)
            memoTrashDao.insertTrashMemo(TrashMemoEntity.fromDomain(memo.copy(isDeleted = true)))
        }

        suspend fun moveToTrashFileOnly(memo: Memo): Boolean {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.dateKey + ".md"
            val cachedUriString = localFileStateDao.getByFilename(filename, false)?.safUri
            val currentFileContent = readMainMemoContent(markdownStorageDataSource, cachedUriString, filename)
            val removedBlock =
                currentFileContent?.let { content ->
                    removeMemoBlockFromContent(content, memo)
                }
            return if (removedBlock != null) {
                // Rewrite MAIN first (atomic). A crash before the subsequent trash append
                // leaves the memo durably removed from main; the outbox-retry path then calls
                // [ensureMemoPresentInTrashFile] with the canonical raw content.
                val remainingContent = removedBlock.remainingContent.trim()
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
                            content = removedBlock.remainingContent,
                            append = false,
                            uri = cachedUriString.toUriOrNull(),
                        )
                    markdownStorageDataSource
                        .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                        ?.let { metadata ->
                            upsertMainFileState(localFileStateDao, filename, metadata.lastModified, savedUri)
                        }
                }

                appendBlockToTrashFile(
                    markdownStorageDataSource,
                    localFileStateDao,
                    filename,
                    removedBlock.blockContent,
                )
                true
            } else {
                // Main file has no matching block: the block was never there, or the file
                // drifted out-of-sync with the DB. Refuse to silently append to trash without
                // proof the memo actually existed in the workspace — the outbox-retry path
                // (with authoritative rawContent on the outbox row) handles recovery via
                // [ensureMemoPresentInTrashFile].
                false
            }
        }

        /**
         * Idempotent finisher used by the outbox retry path. Safe to invoke after a crash
         * between [moveToTrashFileOnly]'s main rewrite and its trash append: if the block is
         * already in the trash file, this is a no-op; otherwise it appends the canonical
         * raw content (authoritative because it lives on the outbox row) so the deletion
         * finishes durably.
         */
        suspend fun ensureMemoPresentInTrashFile(memo: Memo): Boolean {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.dateKey + ".md"
            val trashContent = markdownStorageDataSource.readFileIn(MemoDirectoryType.TRASH, filename)
            if (trashContent != null && trashContent.contains(memo.rawContent)) {
                return true
            }
            appendBlockToTrashFile(
                markdownStorageDataSource = markdownStorageDataSource,
                localFileStateDao = localFileStateDao,
                filename = filename,
                blockContent =
                    buildString {
                        appendLine()
                        append(memo.rawContent)
                        appendLine()
                    },
            )
            return true
        }

        suspend fun restoreFromTrash(memo: Memo) {
            if (!restoreFromTrashFileOnly(memo)) {
                throw UnsafeWorkspaceMutationException("Unable to restore trash memo safely: ${memo.id}")
            }
            restoreFromTrashInDb(memo)
            memoVersionRecorder.enqueueLocalRevision(
                memo = memo.copy(isDeleted = false),
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_RESTORE,
            )
        }

        suspend fun restoreFromTrashInDb(memo: Memo): Boolean {
            val sourceMemo = memoTrashDao.getTrashMemo(memo.id)?.toDomain()?.copy(isDeleted = false) ?: return false
            persistRestoredMainMemo(memoWriteDao, memoTagDao, memoImageDao, sourceMemo)
            memoTrashDao.deleteTrashMemoById(sourceMemo.id)
            return true
        }

        suspend fun restoreFromTrashFileOnly(memo: Memo): Boolean {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.dateKey + ".md"
            val removedBlock =
                markdownStorageDataSource
                    .readFileIn(MemoDirectoryType.TRASH, filename)
                    ?.let { trashContent ->
                        removeMemoBlockFromContent(trashContent, memo)
                    }
            return if (removedBlock == null) {
                Timber.e("restoreMemo: Failed to find memo block in trash file for ${memo.id}")
                false
            } else {
                val remainingTrash = removedBlock.remainingContent.trim()
                if (remainingTrash.isEmpty()) {
                    markdownStorageDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                    localFileStateDao.deleteByFilename(filename, true)
                } else {
                    markdownStorageDataSource.saveFileIn(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        content = removedBlock.remainingContent,
                        append = false,
                    )
                    markdownStorageDataSource
                        .getFileMetadataIn(MemoDirectoryType.TRASH, filename)
                        ?.let { trashMetadata ->
                            upsertTrashFileState(localFileStateDao, filename, trashMetadata.lastModified)
                        }
                }

                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = removedBlock.blockContent,
                    append = true,
                )
                markdownStorageDataSource
                    .getFileMetadataIn(MemoDirectoryType.MAIN, filename)
                    ?.let { metadata ->
                        upsertMainFileState(localFileStateDao, filename, metadata.lastModified)
                    }
                true
            }
        }

        suspend fun deleteFromTrashPermanently(memo: Memo) {
            requireSafeMemoDateKey(memo.dateKey)
            val filename = memo.dateKey + ".md"
            val removedBlock =
                markdownStorageDataSource
                    .readFileIn(MemoDirectoryType.TRASH, filename)
                    ?.let { trashContent ->
                        removeMemoBlockFromContent(trashContent, memo)
                    }

            if (removedBlock == null) {
                Timber.e("deletePermanently: Failed to find block for ${memo.id}")
                throw UnsafeWorkspaceMutationException("Unable to delete trash memo safely: ${memo.id}")
            } else {
                val remainingContent = removedBlock.remainingContent.trim()
                if (remainingContent.isEmpty()) {
                    markdownStorageDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                    localFileStateDao.deleteByFilename(filename, true)
                } else {
                    markdownStorageDataSource.saveFileIn(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        content = removedBlock.remainingContent,
                        append = false,
                    )
                }
                memoImageDao.deleteImageRefsByMemoId(memo.id)
                memoTrashDao.deleteTrashMemoById(memo.id)
            }

            memoVersionRecorder.enqueueLocalRevision(
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

        /**
         * Clears the entire trash in one pass: deletes every trash file (one write per date
         * instead of one per memo) and wipes the LomoTrash table with a single [MemoTrashDao.clearTrash]
         * call. Keeps the per-memo revision enqueue and attachment orphan cleanup so version
         * history and media cleanup still happen exactly as they do for single-memo deletes.
         */
        suspend fun clearAllTrashPermanently(trashMemos: List<TrashMemoEntity>) {
            if (trashMemos.isEmpty()) return

            trashMemos
                .map { it.date }
                .distinct()
                .forEach { dateKey ->
                    requireSafeMemoDateKey(dateKey)
                    val filename = "$dateKey.md"
                    markdownStorageDataSource.deleteFileIn(MemoDirectoryType.TRASH, filename)
                    localFileStateDao.deleteByFilename(filename, true)
                }
            memoImageDao.deleteImageRefsByMemoIds(trashMemos.map(TrashMemoEntity::id))
            memoTrashDao.clearTrash()

            trashMemos.forEach { entity ->
                val domainMemo = entity.toDomain()
                memoVersionRecorder.enqueueLocalRevision(
                    memo = domainMemo.copy(isDeleted = true),
                    lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.DELETED,
                    origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_DELETE,
                )
                deleteUnreferencedAttachments(
                    memo = domainMemo,
                    memoSearchDao = memoSearchDao,
                    mediaStorageDataSource = mediaStorageDataSource,
                )
            }
        }

    }

private data class RemovedMemoBlock(
    val remainingContent: String,
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
): RemovedMemoBlock? {
    val lines = IndexedTextLines.of(content)
    val (startIndex, endIndex) = findDestructiveMemoBlock(lines, memo.rawContent, memo.id)
    return if (startIndex != -1 && endIndex >= startIndex) {
        RemovedMemoBlock(
            remainingContent = rebuildRemainingMemoContent(lines, startIndex, endIndex),
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

private fun rebuildRemainingMemoContent(
    lines: List<String>,
    startIndex: Int,
    endIndex: Int,
): String =
    buildString(lines.sumOf(String::length) + lines.size) {
        for (index in lines.indices) {
            if (index in startIndex..endIndex) {
                continue
            }
            if (isNotEmpty()) {
                append('\n')
            }
            append(lines[index])
        }
    }

private suspend fun deleteUnreferencedAttachments(
    memo: Memo,
    memoSearchDao: MemoSearchDao,
    mediaStorageDataSource: MediaStorageDataSource,
) {
    deleteOrphanAttachments(
        paths = memo.imageUrls,
        excludeMemoId = memo.id,
        memoSearchDao = memoSearchDao,
        mediaStorageDataSource = mediaStorageDataSource,
    )
}

private suspend fun appendBlockToTrashFile(
    markdownStorageDataSource: MarkdownStorageDataSource,
    localFileStateDao: LocalFileStateDao,
    filename: String,
    blockContent: String,
) {
    markdownStorageDataSource.saveFileIn(
        directory = MemoDirectoryType.TRASH,
        filename = filename,
        content = blockContent,
        append = true,
    )
    markdownStorageDataSource
        .getFileMetadataIn(MemoDirectoryType.TRASH, filename)
        ?.let { trashMetadata ->
            upsertTrashFileState(localFileStateDao, filename, trashMetadata.lastModified)
        }
}

private suspend fun persistRestoredMainMemo(
    memoWriteDao: MemoWriteDao,
    memoTagDao: MemoTagDao,
    memoImageDao: MemoImageDao,
    memo: Memo,
) {
    val entity = MemoEntity.fromDomain(memo)
    memoWriteDao.insertMemo(entity)
    memoTagDao.replaceTagRefsForMemo(entity)
    memoImageDao.replaceImageRefsForMemo(entity)
}

private suspend fun upsertMainFileState(
    localFileStateDao: LocalFileStateDao,
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

private suspend fun upsertTrashFileState(
    localFileStateDao: LocalFileStateDao,
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

private fun String?.toUriOrNull(): Uri? = this?.let(Uri::parse)
