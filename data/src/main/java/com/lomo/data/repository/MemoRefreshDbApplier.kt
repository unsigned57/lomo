package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.util.SearchTokenizer

class MemoRefreshDbApplier
    constructor(
        private val dao: MemoDao,
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
        parseResult.mainDatesToReplace.forEach { date ->
            val memoIds = dao.getMemosByDate(date).map { it.id }
            if (memoIds.isNotEmpty()) {
                dao.deleteTagRefsByMemoIds(memoIds)
                dao.deleteMemoFtsByIds(memoIds)
            }
            dao.deleteMemosByDate(date)
        }
        parseResult.trashDatesToReplace.forEach { date ->
            dao.deleteTrashMemosByDate(date)
        }

        val deduplicatedMainMemos =
            parseResult.mainMemos
                .associateBy { it.id }
                .values
                .toList()
        val deduplicatedTrashMemos =
            parseResult.trashMemos
                .associateBy { it.id }
                .values
                .toList()
        val mainIds = deduplicatedMainMemos.map { it.id }.toSet()
        val filteredTrashMemos =
            deduplicatedTrashMemos.filter { trashMemo ->
                trashMemo.id !in mainIds
            }

        if (mainIds.isNotEmpty()) {
            dao.deleteTrashMemosByIds(mainIds.toList())
        }

        if (deduplicatedMainMemos.isNotEmpty()) {
            dao.insertMemos(deduplicatedMainMemos)
            dao.replaceTagRefsForMemos(deduplicatedMainMemos)
            deduplicatedMainMemos.forEach {
                val tokenized = SearchTokenizer.tokenize(it.content)
                dao.insertMemoFts(MemoFtsEntity(it.id, tokenized))
            }
        }

        if (filteredTrashMemos.isNotEmpty()) {
            dao.insertTrashMemos(filteredTrashMemos)
        }

        if (parseResult.metadataToUpdate.isNotEmpty()) {
            localFileStateDao.upsertAll(parseResult.metadataToUpdate)
        }

        filesToDeleteInDb.forEach { (filename, isTrash) ->
            val date = filename.removeSuffix(".md")
            if (isTrash) {
                val trashMemosInDb = dao.getTrashMemosByDate(date)
                val trashMemoIds = trashMemosInDb.map { it.id }
                if (trashMemoIds.isNotEmpty()) {
                    dao.deleteTrashMemosByIds(trashMemoIds)
                }
            } else {
                val memosInDb = dao.getMemosByDate(date)
                val memoIds = memosInDb.map { it.id }
                if (memoIds.isNotEmpty()) {
                    dao.deleteTagRefsByMemoIds(memoIds)
                    dao.deleteMemosByIds(memoIds)
                    dao.deleteMemoFtsByIds(memoIds)
                }
            }
            localFileStateDao.deleteByFilename(filename, isTrash)
        }
    }

}
