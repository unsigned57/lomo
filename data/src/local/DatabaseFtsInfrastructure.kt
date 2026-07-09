package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import com.lomo.data.util.SearchTokenizer

internal fun rebuildMemoFtsExternalContentInfrastructure(db: SQLiteConnection) {
    rebuildMemoFtsExternalContentTable(db)
    createMemoFtsExternalContentTriggers(db)
    rebuildMemoFtsExternalContentIndex(db)
}

internal fun rebuildMemoFtsExternalContentTable(db: SQLiteConnection) {
    dropMemoFtsExternalContentTriggers(db)
    db.execSQL("$DROP_TABLE_IF_EXISTS `$FTS_TABLE`")
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `$FTS_TABLE`
        USING fts5(`$COLUMN_SEARCH_CONTENT`, content='$MEMO_TABLE', content_rowid='rowid', tokenize='unicode61')
        """.trimIndent(),
    )
}

internal fun createMemoFtsExternalContentTriggers(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS `lomo_fts_ai`
        AFTER INSERT ON `$MEMO_TABLE`
        BEGIN
            INSERT INTO `$FTS_TABLE`(`rowid`, `$COLUMN_SEARCH_CONTENT`)
            VALUES (new.rowid, new.`$COLUMN_SEARCH_CONTENT`);
        END;
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS `lomo_fts_au`
        AFTER UPDATE ON `$MEMO_TABLE`
        BEGIN
            INSERT INTO `$FTS_TABLE`(`$FTS_TABLE`, `rowid`, `$COLUMN_SEARCH_CONTENT`)
            VALUES ('delete', old.rowid, old.`$COLUMN_SEARCH_CONTENT`);
            INSERT INTO `$FTS_TABLE`(`rowid`, `$COLUMN_SEARCH_CONTENT`)
            VALUES (new.rowid, new.`$COLUMN_SEARCH_CONTENT`);
        END;
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS `lomo_fts_ad`
        AFTER DELETE ON `$MEMO_TABLE`
        BEGIN
            INSERT INTO `$FTS_TABLE`(`$FTS_TABLE`, `rowid`, `$COLUMN_SEARCH_CONTENT`)
            VALUES ('delete', old.rowid, old.`$COLUMN_SEARCH_CONTENT`);
        END;
        """.trimIndent(),
    )
}

internal fun dropMemoFtsExternalContentTriggers(db: SQLiteConnection) {
    db.execSQL("DROP TRIGGER IF EXISTS `lomo_fts_ai`")
    db.execSQL("DROP TRIGGER IF EXISTS `lomo_fts_au`")
    db.execSQL("DROP TRIGGER IF EXISTS `lomo_fts_ad`")
}

internal fun rebuildMemoFtsExternalContentIndex(db: SQLiteConnection) {
    db.execSQL("INSERT INTO `$FTS_TABLE`(`$FTS_TABLE`) VALUES ('rebuild')")
}

internal fun ensureMemoFts5Infrastructure(db: SQLiteConnection) {
    val existingCreateSql = db.tableCreateSql(FTS_TABLE)
    if (!isMemoFtsExternalContentTableValid(existingCreateSql)) {
        rebuildMemoFtsExternalContentInfrastructure(db)
        return
    }
    if (!db.memoFtsExternalContentTriggersPresent()) {
        createMemoFtsExternalContentTriggers(db)
    }
}

private fun isMemoFtsExternalContentTableValid(createSql: String?): Boolean =
    createSql != null &&
        createSql.contains("USING fts5", ignoreCase = true) &&
        createSql.contains("content='Lomo'", ignoreCase = true) &&
        createSql.contains("content_rowid='rowid'", ignoreCase = true) &&
        createSql.contains(COLUMN_SEARCH_CONTENT, ignoreCase = true)

internal fun SQLiteConnection.memoFtsExternalContentTriggersPresent(): Boolean =
    memoFtsTriggerExists("lomo_fts_ai") &&
        memoFtsTriggerExists("lomo_fts_au") &&
        memoFtsTriggerExists("lomo_fts_ad")

private fun SQLiteConnection.memoFtsTriggerExists(triggerName: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1", arrayOf(triggerName)).use { cursor ->
        cursor.moveToFirst()
    }

internal fun rebuildMemoFtsIndex(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_TABLE)) {
        return
    }
    val cursor =
        db.query(
            """
            SELECT `id`, `$COLUMN_CONTENT`
            FROM `$MEMO_TABLE`
            """.trimIndent(),
        )
    try {
        if (!cursor.moveToFirst()) {
            return
        }
        val memoIdIndex = cursor.getColumnIndex("id")
        val contentIndex = cursor.getColumnIndex(COLUMN_CONTENT)
        do {
            val memoId =
                requireNotNull(cursor.getString(memoIdIndex)) {
                    "Memo id missing while populating FTS index"
                }
            val content =
                requireNotNull(cursor.getString(contentIndex)) {
                    "Memo content missing while populating FTS index"
                }
            db.execSQL(
                "INSERT INTO `$FTS_TABLE` (`memoId`, `$COLUMN_CONTENT`) VALUES (?, ?)",
                arrayOf(memoId, SearchTokenizer.tokenize(content)),
            )
        } while (cursor.moveToNext())
    } finally {
        cursor.close()
    }
}
