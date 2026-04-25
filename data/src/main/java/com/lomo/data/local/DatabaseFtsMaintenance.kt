package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lomo.data.util.SearchTokenizer

internal fun ensureMemoFtsTable(db: SupportSQLiteDatabase) {
    ensureMemoFtsMaintenanceTable(db)

    val rebuiltSchema = !db.memoFtsTableUsesFts5()
    if (rebuiltSchema) {
        rebuildMemoFtsTable(db)
    }
    if (rebuiltSchema || db.memoFtsContentVersion() < CURRENT_MEMO_FTS_CONTENT_VERSION) {
        retokenizeMemoFtsContent(db)
        db.setMemoFtsContentVersion(CURRENT_MEMO_FTS_CONTENT_VERSION)
    }
}

private fun SupportSQLiteDatabase.memoFtsTableUsesFts5(): Boolean =
    query("SELECT sql FROM sqlite_master WHERE name='$FTS_TABLE' LIMIT 1").use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use false
        }
        val createSql = cursor.getString(0).orEmpty()
        createSql.contains("fts5", ignoreCase = true)
    }

private fun ensureMemoFtsMaintenanceTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$MEMO_FTS_MAINTENANCE_TABLE` (
            `id` INTEGER NOT NULL,
            `content_version` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}

private fun SupportSQLiteDatabase.memoFtsContentVersion(): Int =
    query("SELECT `content_version` FROM `$MEMO_FTS_MAINTENANCE_TABLE` WHERE `id` = 1 LIMIT 1").use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use 0
        }
        cursor.getInt(0)
    }

private fun SupportSQLiteDatabase.setMemoFtsContentVersion(version: Int) {
    execSQL(
        "INSERT OR REPLACE INTO `$MEMO_FTS_MAINTENANCE_TABLE`(`id`, `content_version`) VALUES (1, ?)",
        arrayOf(version),
    )
}

private fun retokenizeMemoFtsContent(db: SupportSQLiteDatabase) {
    db.execSQL("DELETE FROM `$FTS_TABLE`")
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }

    db.query("SELECT `id`, `$COLUMN_CONTENT` FROM `$MEMO_TABLE`").use { cursor ->
        val idIndex = cursor.getColumnIndex("id")
        val contentIndex = cursor.getColumnIndex(COLUMN_CONTENT)
        while (cursor.moveToNext()) {
            val memoId = cursor.getString(idIndex)
            val content = cursor.getString(contentIndex).orEmpty()
            db.execSQL(
                "INSERT INTO `$FTS_TABLE`(`memoId`, `$COLUMN_CONTENT`) VALUES (?, ?)",
                arrayOf(memoId, SearchTokenizer.tokenize(content)),
            )
        }
    }
}

private const val MEMO_FTS_MAINTENANCE_TABLE = "lomo_fts_maintenance"
private const val CURRENT_MEMO_FTS_CONTENT_VERSION = 1
