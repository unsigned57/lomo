package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import com.lomo.data.local.entity.MemoFileOutboxIdentity
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp

internal const val LEGACY_MEMOS_TABLE = "memos"
private const val LEGACY_FILE_SYNC_METADATA_TABLE = "file_sync_metadata"
private const val IMAGE_CACHE_TABLE = "image_cache"
private const val TAGS_TABLE = "tags"
private const val LEGACY_MEMO_TAG_CROSS_REF_TABLE = "memo_tag_cross_ref"
private const val LEGACY_MEMO_FTS_TABLE = "memos_fts"
internal const val MEMO_FILE_OUTBOX_TABLE = "MemoFileOutbox"
internal const val MEMO_TAG_CROSS_REF_TABLE = "MemoTagCrossRef"
internal const val MEMO_IMAGE_ATTACHMENT_TABLE = "MemoImageAttachment"
internal const val MEMO_PIN_TABLE = "MemoPin"
internal const val WEBDAV_SYNC_METADATA_TABLE = "webdav_sync_metadata"
internal const val S3_SYNC_METADATA_TABLE = "s3_sync_metadata"
internal const val S3_REMOTE_INDEX_TABLE = "s3_remote_index"
internal const val S3_REMOTE_SHARD_STATE_TABLE = "s3_remote_shard_state"
internal const val S3_SYNC_PROTOCOL_STATE_TABLE = "s3_sync_protocol_state"
internal const val S3_LOCAL_CHANGE_JOURNAL_TABLE = "s3_local_change_journal"
internal const val FTS_TABLE = "lomo_fts"
private const val LOCAL_FILE_STATE_LEGACY_TABLE = "local_file_state_legacy_v22"
private const val MEMO_FILE_OUTBOX_LEGACY_TABLE = "MemoFileOutbox_legacy_v22"
internal const val LEGACY_ROW_ID_TEXT_EXPR = "'legacy_' || rowid"
internal const val CURRENT_TIME_MILLIS_SQL = "(CAST(strftime('%s','now') AS INTEGER) * 1000)"
internal const val UPDATE_OPERATION_SQL = "1"
private const val OUTBOX_COLUMN_ID = 0
private const val OUTBOX_COLUMN_OPERATION = 1
private const val OUTBOX_COLUMN_OPERATION_ID = 2
private const val OUTBOX_COLUMN_IDEMPOTENCY_KEY = 3
private const val OUTBOX_COLUMN_MEMO_ID = 4
private const val OUTBOX_COLUMN_MEMO_DATE = 5
private const val OUTBOX_COLUMN_MEMO_TIMESTAMP = 6
private const val OUTBOX_COLUMN_MEMO_RAW_CONTENT = 7
private const val OUTBOX_COLUMN_NEW_CONTENT = 8
private const val OUTBOX_COLUMN_CREATE_RAW_CONTENT = 9
private const val OUTBOX_COLUMN_CREATED_AT = 10
private const val OUTBOX_COLUMN_UPDATED_AT = 11
private const val OUTBOX_COLUMN_RETRY_COUNT = 12
private const val OUTBOX_COLUMN_LAST_ERROR = 13
private const val OUTBOX_COLUMN_CLAIM_TOKEN = 14
private const val OUTBOX_COLUMN_CLAIM_UPDATED_AT = 15

internal fun migrateLegacyMemosTable(db: SQLiteConnection) {
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

internal fun migrateLegacyFileSyncMetadata(db: SQLiteConnection) {
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

internal fun dropLegacyTables(db: SQLiteConnection) {
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
    db: SQLiteConnection,
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
    db: SQLiteConnection,
    tableName: String,
    withContentIndex: Boolean,
) {
    if (!db.tableExists(tableName)) {
        createMemoTable(db, tableName, withContentIndex)
        return
    }

    val legacyTable = "${tableName}_legacy_v22"
    val columns = db.tableColumns(tableName)
    val hasUpdatedAt = "updatedAt" in columns

    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$tableName` RENAME TO `$legacyTable`")
    db.dropExplicitIndices(legacyTable)
    createMemoTable(
        db = db,
        tableName = tableName,
        withContentIndex = withContentIndex,
        withUpdatedAt = hasUpdatedAt,
    )

    val idExpr = legacyMemoIdExpr(columns)
    val timestampExpr = pickIntExpr(columns, COLUMN_TIMESTAMP)
    val updatedAtExpr =
        if (hasUpdatedAt) {
            pickIntExpr(columns, "updatedAt", defaultExpr = timestampExpr)
        } else {
            null
        }
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
        updatedAtExpr = updatedAtExpr,
        contentExpr = contentExpr,
        rawContentExpr = rawContentExpr,
        dateExpr = dateExpr,
        tagsExpr = tagsExpr,
        imageUrlsExpr = imageUrlsExpr,
    )

    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
}

internal fun normalizeLocalFileStateTable(db: SQLiteConnection) {
    if (!db.tableExists(LOCAL_FILE_STATE_TABLE)) {
        createLocalFileStateTable(db)
        return
    }

    val columns = db.tableColumns(LOCAL_FILE_STATE_TABLE)
    db.execSQL("$DROP_TABLE_IF_EXISTS `$LOCAL_FILE_STATE_LEGACY_TABLE`")
    db.execSQL("ALTER TABLE `$LOCAL_FILE_STATE_TABLE` RENAME TO `$LOCAL_FILE_STATE_LEGACY_TABLE`")
    db.dropExplicitIndices(LOCAL_FILE_STATE_LEGACY_TABLE)
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

internal fun normalizeMemoFileOutboxTable(db: SQLiteConnection) {
    if (!db.tableExists(MEMO_FILE_OUTBOX_TABLE)) {
        createMemoFileOutboxTable(db)
        return
    }

    val columns = db.tableColumns(MEMO_FILE_OUTBOX_TABLE)
    db.execSQL("$DROP_TABLE_IF_EXISTS `$MEMO_FILE_OUTBOX_LEGACY_TABLE`")
    db.execSQL("ALTER TABLE `$MEMO_FILE_OUTBOX_TABLE` RENAME TO `$MEMO_FILE_OUTBOX_LEGACY_TABLE`")
    db.dropExplicitIndices(MEMO_FILE_OUTBOX_LEGACY_TABLE)
    createMemoFileOutboxTable(db)

    val projection = memoFileOutboxProjection(columns)
    migrateMemoFileOutboxRows(db, projection)

    db.execSQL("$DROP_TABLE_IF_EXISTS `$MEMO_FILE_OUTBOX_LEGACY_TABLE`")
}

private fun migrateMemoFileOutboxRows(
    db: SQLiteConnection,
    projection: MemoFileOutboxProjection,
) {
    val rows = readMemoFileOutboxRows(db, projection)
    rows.forEach { row ->
        val identity = row.resolveIdentity()
        db.execSQL(
            """
            INSERT OR IGNORE INTO `$MEMO_FILE_OUTBOX_TABLE` (
                `id`,
                `operation`,
                `operationId`,
                `idempotencyKey`,
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                row.id,
                row.operation.persistedValue,
                identity.operationId,
                identity.idempotencyKey,
                row.memoId,
                row.memoDate,
                row.memoTimestamp,
                row.memoRawContent,
                row.newContent,
                row.createRawContent,
                row.createdAt,
                row.updatedAt,
                row.retryCount,
                row.lastError,
                row.claimToken,
                row.claimUpdatedAt,
            ),
        )
    }
}

private fun readMemoFileOutboxRows(
    db: SQLiteConnection,
    projection: MemoFileOutboxProjection,
): List<MemoFileOutboxMigrationRow> =
    db.query(
        """
        SELECT
            ${projection.idExpr} AS `id`,
            ${projection.operationExpr} AS `operation`,
            ${projection.operationIdExpr} AS `operationId`,
            ${projection.idempotencyKeyExpr} AS `idempotencyKey`,
            ${projection.memoIdExpr} AS `memoId`,
            ${projection.memoDateExpr} AS `memoDate`,
            ${projection.memoTimestampExpr} AS `memoTimestamp`,
            ${projection.memoRawContentExpr} AS `memoRawContent`,
            ${projection.newContentExpr} AS `newContent`,
            ${projection.createRawContentExpr} AS `createRawContent`,
            ${projection.createdAtExpr} AS `createdAt`,
            ${projection.updatedAtExpr} AS `updatedAt`,
            ${projection.retryCountExpr} AS `retryCount`,
            ${projection.lastErrorExpr} AS `lastError`,
            ${projection.claimTokenExpr} AS `claimToken`,
            ${projection.claimUpdatedAtExpr} AS `claimUpdatedAt`
        FROM `$MEMO_FILE_OUTBOX_LEGACY_TABLE`
        ORDER BY `id` ASC
        """.trimIndent(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    MemoFileOutboxMigrationRow(
                        id = cursor.getNullableLong(OUTBOX_COLUMN_ID),
                        operation =
                            MemoFileOutboxOp.fromPersistedValue(
                                requireNotNull(cursor.getNullableInt(OUTBOX_COLUMN_OPERATION)) {
                                    "Memo outbox migration row missing operation"
                                },
                            ),
                        operationId = cursor.getString(OUTBOX_COLUMN_OPERATION_ID),
                        idempotencyKey = cursor.getString(OUTBOX_COLUMN_IDEMPOTENCY_KEY),
                        memoId =
                            requireNotNull(cursor.getString(OUTBOX_COLUMN_MEMO_ID)) {
                                "Memo outbox migration row missing memoId"
                            },
                        memoDate =
                            requireNotNull(cursor.getString(OUTBOX_COLUMN_MEMO_DATE)) {
                                "Memo outbox migration row missing memoDate"
                            },
                        memoTimestamp = cursor.getLong(OUTBOX_COLUMN_MEMO_TIMESTAMP),
                        memoRawContent =
                            requireNotNull(cursor.getString(OUTBOX_COLUMN_MEMO_RAW_CONTENT)) {
                                "Memo outbox migration row missing memoRawContent"
                            },
                        newContent = cursor.getString(OUTBOX_COLUMN_NEW_CONTENT),
                        createRawContent = cursor.getString(OUTBOX_COLUMN_CREATE_RAW_CONTENT),
                        createdAt = cursor.getLong(OUTBOX_COLUMN_CREATED_AT),
                        updatedAt = cursor.getLong(OUTBOX_COLUMN_UPDATED_AT),
                        retryCount = cursor.getInt(OUTBOX_COLUMN_RETRY_COUNT),
                        lastError = cursor.getString(OUTBOX_COLUMN_LAST_ERROR),
                        claimToken = cursor.getString(OUTBOX_COLUMN_CLAIM_TOKEN),
                        claimUpdatedAt = cursor.getNullableLong(OUTBOX_COLUMN_CLAIM_UPDATED_AT),
                    ),
                )
            }
        }
    }

private data class MemoFileOutboxMigrationRow(
    val id: Long?,
    val operation: MemoFileOutboxOp,
    val operationId: String?,
    val idempotencyKey: String?,
    val memoId: String,
    val memoDate: String,
    val memoTimestamp: Long,
    val memoRawContent: String,
    val newContent: String?,
    val createRawContent: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val retryCount: Int,
    val lastError: String?,
    val claimToken: String?,
    val claimUpdatedAt: Long?,
) {
    fun resolveIdentity(): MemoFileOutboxIdentity {
        if (operationId != null || idempotencyKey != null) {
            return MemoFileOutboxIdentity(
                operationId =
                    requireNotNull(operationId) {
                        "Memo outbox migration row missing operationId for $memoId"
                    },
                idempotencyKey =
                    requireNotNull(idempotencyKey) {
                        "Memo outbox migration row missing idempotencyKey for $memoId"
                    },
            )
        }
        return MemoFileOutboxIdentityPolicy.forOutboxOperation(
            operation = operation,
            memoId = memoId,
            memoDate = memoDate,
            memoRawContent = memoRawContent,
            newContent = newContent,
            createRawContent = createRawContent,
        )
    }
}

private fun SQLiteQueryCursor.getNullableLong(index: Int): Long? =
    if (getString(index) == null) null else getLong(index)

private fun SQLiteQueryCursor.getNullableInt(index: Int): Int? =
    if (getString(index) == null) null else getInt(index)
