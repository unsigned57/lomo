package com.lomo.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.util.SearchTokenizer

class MemoRefreshDbApplier
    constructor(
        private val dao: MemoDao,
        private val localFileStateDao: LocalFileStateDao,
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

        runBatchUpdateInTransaction {
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

    private suspend fun runBatchUpdateInTransaction(block: suspend () -> Unit) {
        resolveRoomDatabase()?.withTransaction {
            block()
        } ?: block()
    }

    private fun resolveRoomDatabase(): RoomDatabase? =
        resolveRoomDatabaseFromDao(dao) ?: resolveRoomDatabaseFromDao(localFileStateDao)

    private fun resolveRoomDatabaseFromDao(daoObject: Any): RoomDatabase? {
        var clazz: Class<*>? = daoObject.javaClass
        while (clazz != null) {
            val roomDbField =
                clazz.declaredFields.firstOrNull { field ->
                    RoomDatabase::class.java.isAssignableFrom(field.type)
                }
            if (roomDbField != null) {
                val roomDb =
                    runCatching {
                        roomDbField.isAccessible = true
                        roomDbField.get(daoObject) as? RoomDatabase
                    }.getOrNull()
                if (roomDb != null) {
                    return roomDb
                }
            }
            clazz = clazz.superclass
        }
        return null
    }
}
