package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

internal const val LEGACY_MEMOS_TABLE = "memos"
private const val LEGACY_FILE_SYNC_METADATA_TABLE = "file_sync_metadata"
private const val IMAGE_CACHE_TABLE = "image_cache"
private const val TAGS_TABLE = "tags"
private const val LEGACY_MEMO_TAG_CROSS_REF_TABLE = "memo_tag_cross_ref"
private const val LEGACY_MEMO_FTS_TABLE = "memos_fts"
internal const val MEMO_FILE_OUTBOX_TABLE = "MemoFileOutbox"
internal const val MEMO_TAG_CROSS_REF_TABLE = "MemoTagCrossRef"
internal const val MEMO_PIN_TABLE = "MemoPin"
internal const val WEBDAV_SYNC_METADATA_TABLE = "webdav_sync_metadata"
internal const val S3_SYNC_METADATA_TABLE = "s3_sync_metadata"
internal const val S3_SYNC_PROTOCOL_STATE_TABLE = "s3_sync_protocol_state"
internal const val S3_LOCAL_CHANGE_JOURNAL_TABLE = "s3_local_change_journal"
internal const val FTS_TABLE = "lomo_fts"
private const val LOCAL_FILE_STATE_LEGACY_TABLE = "local_file_state_legacy_v22"
private const val MEMO_FILE_OUTBOX_LEGACY_TABLE = "MemoFileOutbox_legacy_v22"
internal const val LEGACY_ROW_ID_TEXT_EXPR = "'legacy_' || rowid"
internal const val CURRENT_TIME_MILLIS_SQL = "(CAST(strftime('%s','now') AS INTEGER) * 1000)"
internal const val UPDATE_OPERATION_SQL = "'UPDATE'"

internal fun migrateLegacyMemosTable(db: SupportSQLiteDatabase) {
    val columns = db.tableColumns(LEGACY_MEMOS_TABLE)

    createMemoTable(db, MEMO_TABLE, withContentIndex = true)
    createMemoTable(db, TRASH_MEMO_TABLE, withContentIndex = false)

    val idExpr = legacyMemoIdExpr(columns)
    val timestampExpr = pickIntExpr(columns, COLUMN_TIMESTAMP)
    val contentExpr = pickTextExpr(columns, COLUMN_CONTENT, COLUMN_RAW_CONTENT)
    val rawContentExpr = pickTextExpr(columns, COLUMN_RAW_CONTENT, COLUMN_CONTENT)
    val dateExpr = pickTextExpr(columns, "date")
    val tagsExpr = pickTextExpr(columns, "tags")
    val imageUrlsExpr = pickTextExpr(columns, "imageUrls")
    val activeFilter =
        if ("isDeleted" in columns) {
            "WHERE COALESCE(`isDeleted`, 0) = 0"
        } else {
            ""
        }

    insertLegacyMemoRows(
        db = db,
        targetTable = MEMO_TABLE,
        sourceTable = LEGACY_MEMOS_TABLE,
        idExpr = idExpr,
        timestampExpr = timestampExpr,
        contentExpr = contentExpr,
        rawContentExpr = rawContentExpr,
        dateExpr = dateExpr,
        tagsExpr = tagsExpr,
        imageUrlsExpr = imageUrlsExpr,
        whereClause = activeFilter,
    )

    if ("isDeleted" in columns) {
        insertLegacyMemoRows(
            db = db,
            targetTable = TRASH_MEMO_TABLE,
            sourceTable = LEGACY_MEMOS_TABLE,
            idExpr = idExpr,
            timestampExpr = timestampExpr,
            contentExpr = contentExpr,
            rawContentExpr = rawContentExpr,
            dateExpr = dateExpr,
            tagsExpr = tagsExpr,
            imageUrlsExpr = imageUrlsExpr,
            whereClause = "WHERE `isDeleted` = 1",
        )
    }

    db.execSQL("$DROP_TABLE_IF_EXISTS `$LEGACY_MEMOS_TABLE`")
}

internal fun migrateLegacyFileSyncMetadata(db: SupportSQLiteDatabase) {
    if (!db.tableExists(LEGACY_FILE_SYNC_METADATA_TABLE)) return

    if (!db.tableExists(LOCAL_FILE_STATE_TABLE)) {
        createLocalFileStateTable(db)
    }

    val columns = db.tableColumns(LEGACY_FILE_SYNC_METADATA_TABLE)
    val filenameExpr = pickTextExpr(columns, "filename")
    val isTrashExpr = pickIntExpr(columns, "isTrash")
    val lastModifiedExpr = pickIntExpr(columns, "lastModified")

    db.execSQL(
        """
        INSERT OR REPLACE INTO `$LOCAL_FILE_STATE_TABLE` (
            `filename`, `isTrash`, `saf_uri`, `last_known_modified_time`, `missing_since`, `missing_count`, `last_seen_at`
        )
        SELECT
            $filenameExpr,
            $isTrashExpr,
            NULL,
            $lastModifiedExpr,
            NULL,
            0,
            0
        FROM `$LEGACY_FILE_SYNC_METADATA_TABLE`
        """.trimIndent(),
    )

    db.execSQL("$DROP_TABLE_IF_EXISTS `$LEGACY_FILE_SYNC_METADATA_TABLE`")
}

internal fun dropLegacyTables(db: SupportSQLiteDatabase) {
    listOf(
        LEGACY_MEMOS_TABLE,
        IMAGE_CACHE_TABLE,
        TAGS_TABLE,
        LEGACY_MEMO_TAG_CROSS_REF_TABLE,
        LEGACY_MEMO_FTS_TABLE,
        LEGACY_FILE_SYNC_METADATA_TABLE,
    ).forEach { tableName ->
        db.execSQL("$DROP_TABLE_IF_EXISTS `$tableName`")
    }
}

internal fun migrateMemoUpdatedAtColumn(
    db: SupportSQLiteDatabase,
    tableName: String,
) {
    if (!db.tableExists(tableName)) return

    if ("updatedAt" !in db.tableColumns(tableName)) {
        db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
    }
    db.execSQL("UPDATE `$tableName` SET `updatedAt` = `$COLUMN_TIMESTAMP` WHERE `updatedAt` <= 0")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tableName}_updatedAt` ON `$tableName` (`updatedAt`)")
}

internal fun normalizeMemoTable(
    db: SupportSQLiteDatabase,
    tableName: String,
    withContentIndex: Boolean,
) {
    if (!db.tableExists(tableName)) {
        createMemoTable(db, tableName, withContentIndex)
        return
    }

    val legacyTable = "${tableName}_legacy_v22"
    val columns = db.tableColumns(tableName)

    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$tableName` RENAME TO `$legacyTable`")
    createMemoTable(db, tableName, withContentIndex)

    val idExpr = legacyMemoIdExpr(columns)
    val timestampExpr = pickIntExpr(columns, COLUMN_TIMESTAMP)
    val contentExpr = pickTextExpr(columns, COLUMN_CONTENT, COLUMN_RAW_CONTENT)
    val rawContentExpr = pickTextExpr(columns, COLUMN_RAW_CONTENT, COLUMN_CONTENT)
    val dateExpr = pickTextExpr(columns, "date", "dateKey")
    val tagsExpr = pickTextExpr(columns, "tags")
    val imageUrlsExpr = pickTextExpr(columns, "imageUrls")

    insertLegacyMemoRows(
        db = db,
        targetTable = tableName,
        sourceTable = legacyTable,
        idExpr = idExpr,
        timestampExpr = timestampExpr,
        contentExpr = contentExpr,
        rawContentExpr = rawContentExpr,
        dateExpr = dateExpr,
        tagsExpr = tagsExpr,
        imageUrlsExpr = imageUrlsExpr,
    )

    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
}

internal fun normalizeLocalFileStateTable(db: SupportSQLiteDatabase) {
    if (!db.tableExists(LOCAL_FILE_STATE_TABLE)) {
        createLocalFileStateTable(db)
        return
    }

    val columns = db.tableColumns(LOCAL_FILE_STATE_TABLE)
    db.execSQL("$DROP_TABLE_IF_EXISTS `$LOCAL_FILE_STATE_LEGACY_TABLE`")
    db.execSQL("ALTER TABLE `$LOCAL_FILE_STATE_TABLE` RENAME TO `$LOCAL_FILE_STATE_LEGACY_TABLE`")
    createLocalFileStateTable(db)

    val filenameExpr = pickTextExpr(columns, "filename")
    val isTrashExpr = pickIntExpr(columns, "isTrash", "is_trash")
    val safUriExpr = pickNullableTextExpr(columns, "saf_uri", "safUri")
    val lastKnownExpr =
        pickIntExpr(
            columns,
            "last_known_modified_time",
            "lastKnownModifiedTime",
            "lastModified",
        )
    val missingSinceExpr = pickNullableIntExpr(columns, "missing_since", "missingSince")
    val missingCountExpr = pickIntExpr(columns, "missing_count", "missingCount")
    val lastSeenAtExpr = pickIntExpr(columns, "last_seen_at", "lastSeenAt")

    db.execSQL(
        """
        INSERT OR REPLACE INTO `$LOCAL_FILE_STATE_TABLE` (
            `filename`, `isTrash`, `saf_uri`, `last_known_modified_time`, `missing_since`, `missing_count`, `last_seen_at`
        )
        SELECT
            $filenameExpr,
            $isTrashExpr,
            $safUriExpr,
            $lastKnownExpr,
            $missingSinceExpr,
            $missingCountExpr,
            $lastSeenAtExpr
        FROM `$LOCAL_FILE_STATE_LEGACY_TABLE`
        """.trimIndent(),
    )

    db.execSQL("$DROP_TABLE_IF_EXISTS `$LOCAL_FILE_STATE_LEGACY_TABLE`")
}

internal fun normalizeMemoFileOutboxTable(db: SupportSQLiteDatabase) {
    if (!db.tableExists(MEMO_FILE_OUTBOX_TABLE)) {
        createMemoFileOutboxTable(db)
        return
    }

    val columns = db.tableColumns(MEMO_FILE_OUTBOX_TABLE)
    db.execSQL("$DROP_TABLE_IF_EXISTS `$MEMO_FILE_OUTBOX_LEGACY_TABLE`")
    db.execSQL("ALTER TABLE `$MEMO_FILE_OUTBOX_TABLE` RENAME TO `$MEMO_FILE_OUTBOX_LEGACY_TABLE`")
    createMemoFileOutboxTable(db)

    val projection = memoFileOutboxProjection(columns)
    db.execSQL(
        """
        INSERT OR REPLACE INTO `$MEMO_FILE_OUTBOX_TABLE` (
            `id`,
            `operation`,
            `memoId`,
            `memoDate`,
            `memoTimestamp`,
            `memoRawContent`,
            `newContent`,
            `createRawContent`,
            `createdAt`,
            `updatedAt`,
            `retryCount`,
            `lastError`,
            `claimToken`,
            `claimUpdatedAt`
        )
        SELECT
            ${projection.idExpr},
            ${projection.operationExpr},
            ${projection.memoIdExpr},
            ${projection.memoDateExpr},
            ${projection.memoTimestampExpr},
            ${projection.memoRawContentExpr},
            ${projection.newContentExpr},
            ${projection.createRawContentExpr},
            ${projection.createdAtExpr},
            ${projection.updatedAtExpr},
            ${projection.retryCountExpr},
            ${projection.lastErrorExpr},
            ${projection.claimTokenExpr},
            ${projection.claimUpdatedAtExpr}
        FROM `$MEMO_FILE_OUTBOX_LEGACY_TABLE`
        """.trimIndent(),
    )

    db.execSQL("$DROP_TABLE_IF_EXISTS `$MEMO_FILE_OUTBOX_LEGACY_TABLE`")
}
