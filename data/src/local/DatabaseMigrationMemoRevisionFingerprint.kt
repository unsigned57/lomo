package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun migrateMemoRevisionAssetFingerprintColumn(db: SQLiteConnection) {
    if ("assetFingerprint" !in db.tableColumns("memo_revision")) {
        db.execSQL("ALTER TABLE `memo_revision` ADD COLUMN `assetFingerprint` TEXT")
    }
    db.execSQL("DROP INDEX IF EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash`")
    createMemoRevisionIndexes(db)
}
