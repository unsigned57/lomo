package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun compactMemoRevisionContentPreviews(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        UPDATE `memo_revision`
        SET `memoContent` = substr(`memoContent`, 1, 277) || '...'
        WHERE length(`memoContent`) > 280
        """.trimIndent(),
    )
}
