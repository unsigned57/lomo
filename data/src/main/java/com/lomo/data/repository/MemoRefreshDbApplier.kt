package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.util.SearchTokenizer

class MemoRefreshDbApplier
(
        private val memoDao: MemoDao,
        private val memoWriteDao: MemoWriteDao,
        private val memoTagDao: MemoTagDao,
        private val memoFtsDao: MemoFtsDao,
        private val memoTrashDao: MemoTrashDao,
        private val localFileStateDao: LocalFileStateDao,
        private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    ) {
        internal suspend fun apply(
            parseResult: MemoRefreshParseResult,
            filesToDeleteInDb: Set<Pair<String, Boolean>>,
        ) {
            val hasDateReplacements =
                parseResult.mainDatesToReplace.isNotEmpty() || parseResult.trashDatesToReplace.isNotEmpty()
            if (!hasDateReplacements && filesToDeleteInDb.isEmpty()) {
                return
            }

            runInTransaction {
                applyInternal(parseResult, filesToDeleteInDb)
            }
        }

        private suspend fun applyInternal(
            parseResult: MemoRefreshParseResult,
            filesToDeleteInDb: Set<Pair<String, Boolean>>,
        ) {
            replaceDates(parseResult)

            val deduplicatedMainMemos = deduplicateMemos(parseResult.mainMemos)
            val filteredTrashMemos = filterTrashMemos(parseResult.trashMemos, deduplicatedMainMemos)
            val mainIds = deduplicatedMainMemos.map { it.id }.toSet()

            deleteConflictingTrash(mainIds)
            persistMainMemos(deduplicatedMainMemos)
            persistTrashMemos(filteredTrashMemos)
            upsertMetadata(parseResult.metadataToUpdate)
            filesToDeleteInDb.forEach { (filename, isTrash) ->
                deleteFileRecords(filename, isTrash)
            }
        }

        private suspend fun replaceDates(parseResult: MemoRefreshParseResult) {
            parseResult.mainDatesToReplace.forEach { date ->
                deleteMainDate(date)
            }
            parseResult.trashDatesToReplace.forEach { date ->
                memoTrashDao.deleteTrashMemosByDate(date)
            }
        }

        private suspend fun deleteMainDate(date: String) {
            val memoIds = memoDao.getMemosByDate(date).map { it.id }
            if (memoIds.isNotEmpty()) {
                memoTagDao.deleteTagRefsByMemoIds(memoIds)
                memoFtsDao.deleteMemoFtsByIds(memoIds)
            }
            memoWriteDao.deleteMemosByDate(date)
        }

        private suspend fun deleteConflictingTrash(mainIds: Set<String>) {
            if (mainIds.isNotEmpty()) {
                memoTrashDao.deleteTrashMemosByIds(mainIds.toList())
            }
        }

        private suspend fun persistMainMemos(memos: List<MemoEntity>) {
            if (memos.isEmpty()) {
                return
            }

            memoWriteDao.insertMemos(memos)
            memoTagDao.replaceTagRefsForMemos(memos)
            memoFtsDao.replaceMemoFtsBatch(
                memos.map {
                    MemoFtsEntity(
                        memoId = it.id,
                        content = SearchTokenizer.tokenize(it.content),
                    )
                },
            )
        }

        private suspend fun persistTrashMemos(memos: List<TrashMemoEntity>) {
            if (memos.isNotEmpty()) {
                memoTrashDao.insertTrashMemos(memos)
            }
        }

        private suspend fun upsertMetadata(metadata: List<LocalFileStateEntity>) {
            if (metadata.isNotEmpty()) {
                localFileStateDao.upsertAll(metadata)
            }
        }

        private suspend fun deleteFileRecords(
            filename: String,
            isTrash: Boolean,
        ) {
            val date = filename.removeSuffix(".md")
            if (isTrash) {
                deleteTrashDate(date)
            } else {
                deleteMainDateEntries(date)
            }
            localFileStateDao.deleteByFilename(filename, isTrash)
        }

        private suspend fun deleteTrashDate(date: String) {
            val trashMemoIds = memoTrashDao.getTrashMemosByDate(date).map { it.id }
            if (trashMemoIds.isNotEmpty()) {
                memoTrashDao.deleteTrashMemosByIds(trashMemoIds)
            }
        }

        private suspend fun deleteMainDateEntries(date: String) {
            val memoIds = memoDao.getMemosByDate(date).map { it.id }
            if (memoIds.isNotEmpty()) {
                memoTagDao.deleteTagRefsByMemoIds(memoIds)
                memoWriteDao.deleteMemosByIds(memoIds)
                memoFtsDao.deleteMemoFtsByIds(memoIds)
            }
        }
    }

private fun deduplicateMemos(memos: List<MemoEntity>): List<MemoEntity> =
    memos.associateBy { it.id }.values.toList()

private fun filterTrashMemos(
    trashMemos: List<TrashMemoEntity>,
    mainMemos: List<MemoEntity>,
): List<TrashMemoEntity> {
    val mainIds = mainMemos.map { it.id }.toSet()
    return trashMemos
        .associateBy { it.id }
        .values
        .filter { trashMemo -> trashMemo.id !in mainIds }
        .toList()
}
