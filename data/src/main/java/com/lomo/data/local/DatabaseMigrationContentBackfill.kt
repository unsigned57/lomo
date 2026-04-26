package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import com.lomo.data.local.entity.StoredMemoRecovery
import com.lomo.data.util.SearchTokenizer

private const val MEMO_ID_COLUMN_INDEX = 0
private const val MEMO_TIMESTAMP_COLUMN_INDEX = 1
private const val MEMO_CONTENT_COLUMN_INDEX = 2
private const val MEMO_RAW_CONTENT_COLUMN_INDEX = 3
private const val MEMO_DATE_COLUMN_INDEX = 4

internal fun backfillMemoSearchContentTokens(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }

    val cursor =
        db.query(
            """
            SELECT `id`, `$COLUMN_TIMESTAMP`, `$COLUMN_CONTENT`, `$COLUMN_RAW_CONTENT`, `date`
            FROM `$MEMO_TABLE`
            """.trimIndent(),
        )
    try {
        if (!cursor.moveToFirst()) {
            return
        }
        do {
            val memoId =
                requireNotNull(cursor.getString(MEMO_ID_COLUMN_INDEX)) {
                    "Legacy memo id missing during content backfill"
                }
            val storedTimestamp = cursor.getLong(MEMO_TIMESTAMP_COLUMN_INDEX)
            val storedContent =
                requireNotNull(cursor.getString(MEMO_CONTENT_COLUMN_INDEX)) {
                    "Legacy memo content missing during content backfill"
                }
            val rawContent =
                requireNotNull(cursor.getString(MEMO_RAW_CONTENT_COLUMN_INDEX)) {
                    "Legacy memo raw content missing during content backfill"
                }
            val dateKey =
                requireNotNull(cursor.getString(MEMO_DATE_COLUMN_INDEX)) {
                    "Legacy memo date missing during content backfill"
                }
            val visibleContent =
                StoredMemoRecovery.recoverOrNull(
                    rawContent = rawContent,
                    storedContent = storedContent,
                    storedTimestamp = storedTimestamp,
                    dateKey = dateKey,
                )?.content ?: storedContent
            if (visibleContent != storedContent) {
                db.execSQL(
                    "UPDATE `$MEMO_TABLE` SET `$COLUMN_CONTENT` = ? WHERE `id` = ?",
                    arrayOf(visibleContent, memoId),
                )
            }
        } while (cursor.moveToNext())
    } finally {
        cursor.close()
    }
}

internal fun ensureMemoSearchContentColumn(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    if (COLUMN_SEARCH_CONTENT !in db.tableColumns(MEMO_TABLE)) {
        db.execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_SEARCH_CONTENT` TEXT NOT NULL DEFAULT ''")
    }
}

internal fun backfillMemoSearchContentColumn(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    ensureMemoSearchContentColumn(db)

    db.query("SELECT `id`, `$COLUMN_CONTENT`, `$COLUMN_SEARCH_CONTENT` FROM `$MEMO_TABLE`").use { cursor ->
        val idIndex = cursor.getColumnIndex("id")
        val contentIndex = cursor.getColumnIndex(COLUMN_CONTENT)
        val searchContentIndex = cursor.getColumnIndex(COLUMN_SEARCH_CONTENT)
        while (cursor.moveToNext()) {
            val memoId = cursor.getString(idIndex) ?: continue
            val content = cursor.getString(contentIndex).orEmpty()
            val searchContent = cursor.getString(searchContentIndex).orEmpty()
            val tokenizedContent = SearchTokenizer.tokenize(content)
            if (tokenizedContent != searchContent) {
                db.execSQL(
                    "UPDATE `$MEMO_TABLE` SET `$COLUMN_SEARCH_CONTENT` = ? WHERE `id` = ?",
                    arrayOf(tokenizedContent, memoId),
                )
            }
        }
    }
}
