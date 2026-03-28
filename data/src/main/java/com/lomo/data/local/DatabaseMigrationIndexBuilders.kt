package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun createMemoRevisionIndexes(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_createdAt`
        ON `memo_revision` (`memoId`, `createdAt`)
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_createdAt_revisionId`
        ON `memo_revision` (`memoId`, `createdAt`, `revisionId`)
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash`
        ON `memo_revision` (`memoId`, `lifecycleState`, `contentHash`, `rawMarkdownBlobHash`)
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_memo_revision_commitId` ON `memo_revision` (`commitId`)")
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_memo_revision_rawMarkdownBlobHash`
        ON `memo_revision` (`rawMarkdownBlobHash`)
        """.trimIndent(),
    )
}

internal fun createMemoRevisionAssetIndexes(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_memo_revision_asset_blobHash`
        ON `memo_revision_asset` (`blobHash`)
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_memo_revision_asset_revisionId_logicalPath`
        ON `memo_revision_asset` (`revisionId`, `logicalPath`)
        """.trimIndent(),
    )
}
