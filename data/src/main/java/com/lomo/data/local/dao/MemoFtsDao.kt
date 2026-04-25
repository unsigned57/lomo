package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.lomo.data.local.entity.MemoFtsEntity

@Dao
interface MemoFtsDao {
    @RawQuery
    suspend fun execute(statement: SupportSQLiteQuery): Int

    @Transaction
    suspend fun insertMemoFts(fts: MemoFtsEntity) {
        deleteMemoFts(fts.memoId)
        execute(
            SimpleSQLiteQuery(
                "INSERT INTO lomo_fts(memoId, content) VALUES (?, ?)",
                arrayOf(fts.memoId, fts.content),
            ),
        )
    }

    @Transaction
    suspend fun replaceMemoFtsBatch(entries: List<MemoFtsEntity>) {
        if (entries.isEmpty()) return
        deleteMemoFtsByIds(entries.map { it.memoId })
        entries.forEach { entry ->
            execute(
                SimpleSQLiteQuery(
                    "INSERT INTO lomo_fts(memoId, content) VALUES (?, ?)",
                    arrayOf(entry.memoId, entry.content),
                ),
            )
        }
    }

    suspend fun deleteMemoFts(memoId: String) {
        execute(
            SimpleSQLiteQuery(
                "DELETE FROM lomo_fts WHERE memoId = ?",
                arrayOf(memoId),
            ),
        )
    }

    suspend fun deleteMemoFtsByIds(memoIds: List<String>) {
        if (memoIds.isEmpty()) return
        val placeholders = List(memoIds.size) { "?" }.joinToString(", ")
        execute(
            SimpleSQLiteQuery(
                "DELETE FROM lomo_fts WHERE memoId IN ($placeholders)",
                memoIds.toTypedArray(),
            ),
        )
    }

    suspend fun clearFts() {
        execute(SimpleSQLiteQuery("DELETE FROM lomo_fts"))
    }
}
