package com.lomo.data.local

import androidx.room3.PooledConnection
import androidx.room3.executeSQL

internal suspend fun createMemoFtsExternalContentTriggers(db: PooledConnection) {
    db.execSql(
        """
        CREATE TRIGGER IF NOT EXISTS `lomo_fts_ai`
        AFTER INSERT ON `$MEMO_TABLE`
        BEGIN
            INSERT INTO `$FTS_TABLE`(`rowid`, `$COLUMN_SEARCH_CONTENT`)
            VALUES (new.rowid, new.`$COLUMN_SEARCH_CONTENT`);
        END;
        """.trimIndent(),
    )
    db.execSql(
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
    db.execSql(
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

internal suspend fun dropMemoFtsExternalContentTriggers(db: PooledConnection) {
    db.execSql("DROP TRIGGER IF EXISTS `lomo_fts_ai`")
    db.execSql("DROP TRIGGER IF EXISTS `lomo_fts_au`")
    db.execSql("DROP TRIGGER IF EXISTS `lomo_fts_ad`")
}

internal suspend fun rebuildMemoFtsExternalContentIndex(db: PooledConnection) {
    db.execSql("INSERT INTO `$FTS_TABLE`(`$FTS_TABLE`) VALUES ('rebuild')")
}

private suspend fun PooledConnection.execSql(sql: String) {
    executeSQL(sql)
}
