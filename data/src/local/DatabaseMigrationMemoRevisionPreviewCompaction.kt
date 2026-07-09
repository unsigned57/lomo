package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun compactMemoRevisionContentPreviews(db: SQLiteConnection) {
    db.execSQL(
        """
        UPDATE `memo_revision`
        SET `memoContent` = substr(`memoContent`, 1, 277) || '...'
        WHERE length(`memoContent`) > 280
        """.trimIndent(),
    )
}
