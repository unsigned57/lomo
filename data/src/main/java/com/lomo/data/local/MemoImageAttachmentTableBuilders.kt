package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun rebuildMemoImageAttachmentTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$MEMO_IMAGE_ATTACHMENT_TABLE`")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$MEMO_IMAGE_ATTACHMENT_TABLE` (
            `memoId` TEXT NOT NULL,
            `imagePath` TEXT NOT NULL,
            PRIMARY KEY(`memoId`, `imagePath`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_MemoImageAttachment_memoId` " +
            "ON `$MEMO_IMAGE_ATTACHMENT_TABLE` (`memoId`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_MemoImageAttachment_imagePath` " +
            "ON `$MEMO_IMAGE_ATTACHMENT_TABLE` (`imagePath`)",
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `$MEMO_IMAGE_ATTACHMENT_TABLE` (`memoId`, `imagePath`)
        WITH RECURSIVE split(memoId, rest, imagePath) AS (
            SELECT `id`, `imageUrls` || ',', ''
            FROM `$MEMO_TABLE`
            WHERE `imageUrls` IS NOT NULL AND `imageUrls` != ''
            UNION ALL
            SELECT `id`, `imageUrls` || ',', ''
            FROM `$TRASH_MEMO_TABLE`
            WHERE `imageUrls` IS NOT NULL AND `imageUrls` != ''
            UNION ALL
            SELECT memoId,
                   substr(rest, instr(rest, ',') + 1),
                   trim(substr(rest, 1, instr(rest, ',') - 1))
            FROM split
            WHERE rest != ''
        )
        SELECT memoId, imagePath
        FROM split
        WHERE imagePath != ''
        """.trimIndent(),
    )
}
