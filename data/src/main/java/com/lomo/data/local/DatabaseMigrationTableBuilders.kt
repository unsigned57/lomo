package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun createMemoTable(
    db: SupportSQLiteDatabase,
    tableName: String,
    withContentIndex: Boolean,
) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$tableName` (
            `id` TEXT NOT NULL,
            `$COLUMN_TIMESTAMP` INTEGER NOT NULL,
            `$COLUMN_CONTENT` TEXT NOT NULL,
            `$COLUMN_RAW_CONTENT` TEXT NOT NULL,
            `date` TEXT NOT NULL,
            `tags` TEXT NOT NULL,
            `imageUrls` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_${tableName}_$COLUMN_TIMESTAMP` " +
            "ON `$tableName` (`$COLUMN_TIMESTAMP`)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tableName}_date` ON `$tableName` (`date`)")
    if (withContentIndex) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_${tableName}_$COLUMN_CONTENT` " +
                "ON `$tableName` (`$COLUMN_CONTENT`)",
        )
    }
}

internal fun createLocalFileStateTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$LOCAL_FILE_STATE_TABLE` (
            `filename` TEXT NOT NULL,
            `isTrash` INTEGER NOT NULL,
            `saf_uri` TEXT,
            `last_known_modified_time` INTEGER NOT NULL,
            `missing_since` INTEGER,
            `missing_count` INTEGER NOT NULL,
            `last_seen_at` INTEGER NOT NULL,
            PRIMARY KEY(`filename`, `isTrash`)
        )
        """.trimIndent(),
    )
}

internal fun createMemoFileOutboxTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$MEMO_FILE_OUTBOX_TABLE` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `operation` TEXT NOT NULL,
            `memoId` TEXT NOT NULL,
            `memoDate` TEXT NOT NULL,
            `memoTimestamp` INTEGER NOT NULL,
            `memoRawContent` TEXT NOT NULL,
            `newContent` TEXT,
            `createRawContent` TEXT,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `retryCount` INTEGER NOT NULL,
            `lastError` TEXT,
            `claimToken` TEXT,
            `claimUpdatedAt` INTEGER
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoFileOutbox_memoId` ON `$MEMO_FILE_OUTBOX_TABLE` (`memoId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoFileOutbox_createdAt` ON `$MEMO_FILE_OUTBOX_TABLE` (`createdAt`)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_MemoFileOutbox_claimToken` " +
            "ON `$MEMO_FILE_OUTBOX_TABLE` (`claimToken`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_MemoFileOutbox_claimUpdatedAt` " +
            "ON `$MEMO_FILE_OUTBOX_TABLE` (`claimUpdatedAt`)",
    )
}

internal fun createMemoPinTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$MEMO_PIN_TABLE` (
            `memoId` TEXT NOT NULL,
            `pinnedAt` INTEGER NOT NULL,
            PRIMARY KEY(`memoId`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoPin_pinnedAt` ON `$MEMO_PIN_TABLE` (`pinnedAt`)")
}

internal fun createWebDavSyncMetadataTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$WEBDAV_SYNC_METADATA_TABLE` (
            `relative_path` TEXT NOT NULL,
            `remote_path` TEXT NOT NULL,
            `etag` TEXT,
            `remote_last_modified` INTEGER,
            `local_last_modified` INTEGER,
            `local_fingerprint` TEXT,
            `last_synced_at` INTEGER NOT NULL,
            `last_resolved_direction` TEXT NOT NULL,
            `last_resolved_reason` TEXT NOT NULL,
            PRIMARY KEY(`relative_path`)
        )
        """.trimIndent(),
    )
}

internal fun createMemoVersionCommitTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `version_commit` (
            `commitId` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `origin` TEXT NOT NULL,
            `actor` TEXT NOT NULL,
            `batchId` TEXT,
            `summary` TEXT NOT NULL,
            PRIMARY KEY(`commitId`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_version_commit_createdAt` ON `version_commit` (`createdAt`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_version_commit_batchId` ON `version_commit` (`batchId`)")
}

internal fun createMemoVersionBlobTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `memo_version_blob` (
            `blobHash` TEXT NOT NULL,
            `storagePath` TEXT NOT NULL,
            `byteSize` INTEGER NOT NULL,
            `contentEncoding` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`blobHash`)
        )
        """.trimIndent(),
    )
}

internal fun createMemoRevisionTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `memo_revision` (
            `revisionId` TEXT NOT NULL,
            `memoId` TEXT NOT NULL,
            `parentRevisionId` TEXT,
            `commitId` TEXT NOT NULL,
            `dateKey` TEXT NOT NULL,
            `lifecycleState` TEXT NOT NULL,
            `rawMarkdownBlobHash` TEXT NOT NULL,
            `contentHash` TEXT NOT NULL,
            `assetFingerprint` TEXT,
            `memoTimestamp` INTEGER NOT NULL,
            `memoUpdatedAt` INTEGER NOT NULL,
            `memoContent` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`revisionId`)
        )
        """.trimIndent(),
    )
    createMemoRevisionIndexes(db)
}

internal fun createMemoRevisionAssetTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `memo_revision_asset` (
            `revisionId` TEXT NOT NULL,
            `logicalPath` TEXT NOT NULL,
            `blobHash` TEXT NOT NULL,
            `contentEncoding` TEXT NOT NULL,
            PRIMARY KEY(`revisionId`, `logicalPath`)
        )
        """.trimIndent(),
    )
    createMemoRevisionAssetIndexes(db)
}

internal fun rebuildMemoFtsTable(db: SupportSQLiteDatabase) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$FTS_TABLE`")
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `$FTS_TABLE`
        USING FTS4(`memoId` TEXT NOT NULL, `$COLUMN_CONTENT` TEXT NOT NULL, tokenize=unicode61)
        """.trimIndent(),
    )
    if (db.tableExists(MEMO_TABLE)) {
        db.execSQL(
            """
            INSERT INTO `$FTS_TABLE` (`memoId`, `$COLUMN_CONTENT`)
            SELECT `id`, `$COLUMN_CONTENT`
            FROM `$MEMO_TABLE`
            """.trimIndent(),
        )
    }
}

internal fun rebuildMemoTagCrossRefTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$MEMO_TAG_CROSS_REF_TABLE` (
            `memoId` TEXT NOT NULL,
            `tag` TEXT NOT NULL,
            PRIMARY KEY(`memoId`, `tag`),
            FOREIGN KEY(`memoId`) REFERENCES `$MEMO_TABLE`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoTagCrossRef_memoId` ON `$MEMO_TAG_CROSS_REF_TABLE` (`memoId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoTagCrossRef_tag` ON `$MEMO_TAG_CROSS_REF_TABLE` (`tag`)")
    db.execSQL(
        """
        INSERT OR IGNORE INTO $MEMO_TAG_CROSS_REF_TABLE(memoId, tag)
        WITH RECURSIVE split(memoId, rest, tag) AS (
            SELECT id, tags || ',', ''
            FROM $MEMO_TABLE
            WHERE tags IS NOT NULL AND tags != ''
            UNION ALL
            SELECT memoId,
                   substr(rest, instr(rest, ',') + 1),
                   trim(substr(rest, 1, instr(rest, ',') - 1))
            FROM split
            WHERE rest != ''
        )
        SELECT memoId, tag
        FROM split
        WHERE tag != ''
        """.trimIndent(),
    )
}
