package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun rebuildMemoFts4Table(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$FTS_TABLE`")
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `$FTS_TABLE`
        USING FTS4(`memoId`, `$COLUMN_CONTENT`, tokenize=unicode61, notindexed=`memoId`)
        """.trimIndent(),
    )
    rebuildMemoFtsIndex(db)
}

/**
 * Legacy v51 schema builder kept for migration-history reproducibility (50 -> 51).
 */
internal fun rebuildMemoFts5Table(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$FTS_TABLE`")
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `$FTS_TABLE`
        USING fts5(`memoId` UNINDEXED, `$COLUMN_CONTENT`, tokenize='unicode61')
        """.trimIndent(),
    )
    rebuildMemoFtsIndex(db)
}
