package com.lomo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lomo.data.local.entity.S3SyncProtocolStateEntity

const val SCHEMA_VERSION_18 = 18
const val SCHEMA_VERSION_19 = 19
const val SCHEMA_VERSION_20 = 20
const val SCHEMA_VERSION_21 = 21
const val SCHEMA_VERSION_22 = 22
const val SCHEMA_VERSION_23 = 23
const val SCHEMA_VERSION_24 = 24
const val SCHEMA_VERSION_25 = 25
const val SCHEMA_VERSION_26 = 26
const val SCHEMA_VERSION_27 = 27
const val SCHEMA_VERSION_28 = 28
const val SCHEMA_VERSION_29 = 29
const val SCHEMA_VERSION_30 = 30
const val SCHEMA_VERSION_31 = 31
const val SCHEMA_VERSION_34 = 34
const val SCHEMA_VERSION_35 = 35
const val SCHEMA_VERSION_36 = 36
const val SCHEMA_VERSION_37 = 37
const val SCHEMA_VERSION_38 = 38
const val SCHEMA_VERSION_39 = 39
const val SCHEMA_VERSION_40 = 40
const val SCHEMA_VERSION_41 = 41
const val SCHEMA_VERSION_42 = 42
const val SCHEMA_VERSION_43 = 43
const val SCHEMA_VERSION_44 = 44

const val MEMO_TABLE = "Lomo"
const val TRASH_MEMO_TABLE = "LomoTrash"
const val LOCAL_FILE_STATE_TABLE = "local_file_state"
const val DROP_TABLE_IF_EXISTS = "DROP TABLE IF EXISTS"
const val COLUMN_TIMESTAMP = "timestamp"
const val COLUMN_CONTENT = "content"
const val COLUMN_RAW_CONTENT = "rawContent"

val MIGRATION_18_19: Migration =
    object : Migration(SCHEMA_VERSION_18, SCHEMA_VERSION_19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `MemoTagCrossRef` (
                    `memoId` TEXT NOT NULL,
                    `tag` TEXT NOT NULL,
                    PRIMARY KEY(`memoId`, `tag`),
                    FOREIGN KEY(`memoId`) REFERENCES `Lomo`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoTagCrossRef_memoId` ON `MemoTagCrossRef` (`memoId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoTagCrossRef_tag` ON `MemoTagCrossRef` (`tag`)")

            // Backfill cross-ref rows from legacy comma-separated tags.
            db.execSQL(
                """
                INSERT OR IGNORE INTO MemoTagCrossRef(memoId, tag)
                WITH RECURSIVE split(memoId, rest, tag) AS (
                    SELECT id, tags || ',', ''
                    FROM Lomo
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
    }

val MIGRATION_19_20: Migration =
    object : Migration(SCHEMA_VERSION_19, SCHEMA_VERSION_20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS image_cache")
        }
    }

val MIGRATION_20_21: Migration =
    object : Migration(SCHEMA_VERSION_20, SCHEMA_VERSION_21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `MemoFileOutbox` (
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
                    `lastError` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoFileOutbox_memoId` ON `MemoFileOutbox` (`memoId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoFileOutbox_createdAt` ON `MemoFileOutbox` (`createdAt`)")
        }
    }

val MIGRATION_21_22: Migration =
    object : Migration(SCHEMA_VERSION_21, SCHEMA_VERSION_22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Normalize schema for users who may have diverged local schemas on v21 builds.
            db.execSQL("$DROP_TABLE_IF_EXISTS `MemoTagCrossRef`")

            normalizeMemoTable(db, tableName = MEMO_TABLE, withContentIndex = true)
            normalizeMemoTable(db, tableName = TRASH_MEMO_TABLE, withContentIndex = false)
            normalizeLocalFileStateTable(db)
            normalizeMemoFileOutboxTable(db)
            rebuildMemoFtsTable(db)
            rebuildMemoTagCrossRefTable(db)
        }
    }

val MIGRATION_22_23: Migration =
    object : Migration(SCHEMA_VERSION_22, SCHEMA_VERSION_23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`")
        }
    }

val MIGRATION_23_24: Migration =
    object : Migration(SCHEMA_VERSION_23, SCHEMA_VERSION_24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateMemoUpdatedAtColumn(db, tableName = MEMO_TABLE)
            migrateMemoUpdatedAtColumn(db, tableName = TRASH_MEMO_TABLE)
        }
    }

val MIGRATION_24_25: Migration =
    object : Migration(SCHEMA_VERSION_24, SCHEMA_VERSION_25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            normalizeMemoFileOutboxTable(db)
        }
    }

val MIGRATION_25_26: Migration =
    object : Migration(SCHEMA_VERSION_25, SCHEMA_VERSION_26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createMemoPinTable(db)
        }
    }

val MIGRATION_26_27: Migration =
    object : Migration(SCHEMA_VERSION_26, SCHEMA_VERSION_27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createWebDavSyncMetadataTable(db)
        }
    }

val MIGRATION_28_29: Migration =
    object : Migration(SCHEMA_VERSION_28, SCHEMA_VERSION_29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createMemoVersionTables(db)
        }
    }

val MIGRATION_29_30: Migration =
    object : Migration(SCHEMA_VERSION_29, SCHEMA_VERSION_30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropRetiredWorkspaceHistoryTables(db)
        }
    }

val MIGRATION_30_31: Migration =
    object : Migration(SCHEMA_VERSION_30, SCHEMA_VERSION_31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createMemoRevisionIndexes(db)
            createMemoRevisionAssetIndexes(db)
        }
    }

val MIGRATION_34_35: Migration =
    object : Migration(SCHEMA_VERSION_34, SCHEMA_VERSION_35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            compactMemoRevisionContentPreviews(db)
        }
    }

val MIGRATION_35_36: Migration =
    object : Migration(SCHEMA_VERSION_35, SCHEMA_VERSION_36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createS3SyncMetadataTable(db)
        }
    }

val MIGRATION_36_37: Migration =
    object : Migration(SCHEMA_VERSION_36, SCHEMA_VERSION_37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            normalizeLocalFileStateTable(db)
        }
    }

val MIGRATION_37_38: Migration =
    object : Migration(SCHEMA_VERSION_37, SCHEMA_VERSION_38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createS3SyncProtocolStateTable(db)
            createS3LocalChangeJournalTable(db)
        }
    }

val MIGRATION_38_39: Migration =
    object : Migration(SCHEMA_VERSION_38, SCHEMA_VERSION_39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateMemoRevisionAssetFingerprintColumn(db)
        }
    }

val MIGRATION_39_40: Migration =
    object : Migration(SCHEMA_VERSION_39, SCHEMA_VERSION_40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createS3RemoteIndexTable(db)
            db.execSQL("ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` ADD COLUMN `last_fast_sync_at` INTEGER")
            db.execSQL("ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` ADD COLUMN `last_reconcile_at` INTEGER")
            db.execSQL("ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` ADD COLUMN `last_full_remote_scan_at` INTEGER")
            db.execSQL("ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` ADD COLUMN `remote_scan_cursor` TEXT")
            db.execSQL(
                "ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` ADD COLUMN `scan_epoch` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

val MIGRATION_40_41: Migration =
    object : Migration(SCHEMA_VERSION_40, SCHEMA_VERSION_41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            normalizeS3SyncProtocolStateTable(db)
        }
    }

val MIGRATION_41_42: Migration =
    object : Migration(SCHEMA_VERSION_41, SCHEMA_VERSION_42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createS3RemoteShardStateTable(db)
        }
    }

val MIGRATION_42_43: Migration =
    object : Migration(SCHEMA_VERSION_42, SCHEMA_VERSION_43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addS3RemoteShardTelemetryColumns(db)
        }
    }

val MIGRATION_43_44: Migration =
    object : Migration(SCHEMA_VERSION_43, SCHEMA_VERSION_44) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createPendingSyncConflictTable(db)
        }
    }

/**
 * Consolidation migrations that bring ANY schema version directly to the
 * current [MEMO_DATABASE_VERSION] in a single step.
 *
 * Room resolves migration paths using shortest-path (fewest hops).
 * Because each of these migrations jumps directly from version N to
 * [MEMO_DATABASE_VERSION], Room will always prefer them over the
 * incremental chain (e.g. 21→22→23→24), eliminating fragile multi-step
 * migrations that can fail at intermediate points.
 *
 * The implementation is detection-based: it inspects actual table names
 * and column names at runtime rather than assuming a specific schema layout,
 * so it handles all intermediate schema states safely.
 */
private val CONSOLIDATION_MIGRATIONS: Array<Migration> =
    (1 until MEMO_DATABASE_VERSION)
        .map { startVersion ->
            object : Migration(startVersion, MEMO_DATABASE_VERSION) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    consolidateToCurrentSchema(db)
                }
            }
        }.toTypedArray()

val ALL_DATABASE_MIGRATIONS: Array<Migration> =
    CONSOLIDATION_MIGRATIONS +
        arrayOf(
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
        )

/**
 * Brings any schema state directly to the current version ([MEMO_DATABASE_VERSION]).
 *
 * This function is structured in phases that mirror the schema evolution history
 * but executes them all atomically in a single migration step:
 *
 * Phase A: Normalize all tables to the v22 baseline schema.
 * Phase B: Apply v22→v23 changes (drop content index).
 * Phase C: Apply v23→v24 changes (add updatedAt column).
 * Phase D: Apply v24→v25 changes (outbox claim columns).
 * Phase E: Apply v25→v26 changes (memo pin table).
 * Phase F: Apply v26→v27 changes (WebDAV sync metadata table).
 * Phase G: Apply v28→v29 changes (memo revision history tables).
 * Phase H: Apply v29→v30 changes (retire legacy workspace-history metadata tables).
 * Phase I: Apply v30→v31 changes (tighten memo revision indexes).
 * Phase J: Apply v34→v35 changes (compact memo-revision previews).
 * Phase K: Apply v35→v36 changes (S3 sync metadata table).
 * Phase L: Apply v36→v37 changes (external refresh protection state).
 * Phase M: Apply v37→v38 changes (S3 incremental protocol/journal tables).
 * Phase N: Apply v38→v39 changes (memo revision asset fingerprints).
 * Phase O: Apply v39→v40 changes (S3 remote index and richer protocol state).
 * Phase P: Apply v41→v42 changes (S3 remote shard reconcile state).
 * Phase Q: Apply v42→v43 changes (richer shard scheduling telemetry).
 * Phase R: Apply v43→v44 changes (pending sync conflict persistence).
 *
 * When adding a new schema version, append a new Phase here.
 */
private fun consolidateToCurrentSchema(db: SupportSQLiteDatabase) {
    // ── Phase A: Bring all tables to v22 normalization baseline ──────

    // A1: Handle old "memos" table (v7-era) with isDeleted column.
    val hadLegacyMemos = db.tableExists(LEGACY_MEMOS_TABLE)
    if (hadLegacyMemos) {
        migrateLegacyMemosTable(db)
    }

    // A2: Normalize Lomo/LomoTrash.
    // If just created from "memos", skip (already correct v22 schema).
    // If existed from an intermediate version, normalize columns.
    db.execSQL("$DROP_TABLE_IF_EXISTS `MemoTagCrossRef`")
    if (!hadLegacyMemos) {
        normalizeMemoTable(db, tableName = MEMO_TABLE, withContentIndex = true)
        normalizeMemoTable(db, tableName = TRASH_MEMO_TABLE, withContentIndex = false)
    }

    // A3: Migrate file_sync_metadata → local_file_state.
    migrateLegacyFileSyncMetadata(db)
    normalizeLocalFileStateTable(db)

    // A4: Normalize MemoFileOutbox.
    normalizeMemoFileOutboxTable(db)

    // A5: Rebuild derived tables from normalized Lomo data.
    rebuildMemoFtsTable(db)
    rebuildMemoTagCrossRefTable(db)

    // A6: Drop any remaining legacy tables.
    dropLegacyTables(db)

    // ── Phase B: v22 → v23 (drop non-B-tree friendly content index) ──
    db.execSQL("DROP INDEX IF EXISTS `index_${MEMO_TABLE}_content`")

    // ── Phase C: v23 → v24 (add updatedAt column) ───────────────────
    migrateMemoUpdatedAtColumn(db, tableName = MEMO_TABLE)
    migrateMemoUpdatedAtColumn(db, tableName = TRASH_MEMO_TABLE)

    // ── Phase D: v24 → v25 (outbox claim columns) ───────────────────
    normalizeMemoFileOutboxTable(db)

    // ── Phase E: v25 → v26 (memo pin table) ─────────────────────────
    createMemoPinTable(db)

    // ── Phase F: v26 → v27 (WebDAV sync metadata) ────────────────────
    createWebDavSyncMetadataTable(db)

    // ── Phase G: v28 → v29 (memo revision history) ──────────────────
    createMemoVersionTables(db)

    // ── Phase H: v29 → v30 (retire legacy workspace history) ───────
    dropRetiredWorkspaceHistoryTables(db)

    // ── Phase I: v30 → v31 (memo revision indexes) ─────────────────
    createMemoRevisionIndexes(db)
    createMemoRevisionAssetIndexes(db)

    // ── Phase J: v34 → v35 (compact memo-revision previews) ────────
    compactMemoRevisionContentPreviews(db)

    // ── Phase K: v35 → v36 (S3 sync metadata table) ────────────────
    createS3SyncMetadataTable(db)

    // ── Phase L: v36 → v37 (external refresh protection state) ────
    normalizeLocalFileStateTable(db)

    // ── Phase M: v37 → v38 (S3 incremental local state) ───────────
    createS3SyncProtocolStateTable(db)
    createS3LocalChangeJournalTable(db)

    // ── Phase N: v38 → v39 (memo revision asset fingerprints) ─────
    migrateMemoRevisionAssetFingerprintColumn(db)

    // ── Phase O: v39 → v40 (S3 remote index and richer protocol state) ──
    createS3RemoteIndexTable(db)
    normalizeS3SyncProtocolStateTable(db)

    // ── Phase P: v41 → v42 (S3 remote shard reconcile state) ────────────
    createS3RemoteShardStateTable(db)

    // ── Phase Q: v42 → v43 (richer shard scheduling telemetry) ──────────
    addS3RemoteShardTelemetryColumns(db)

    // ── Phase R: v43 → v44 (pending sync conflict persistence) ──────────
    createPendingSyncConflictTable(db)
}

private fun normalizeS3SyncProtocolStateTable(db: SupportSQLiteDatabase) {
    if (!db.tableExists(S3_SYNC_PROTOCOL_STATE_TABLE)) {
        createS3SyncProtocolStateTable(db)
        return
    }
    val columns = db.tableColumns(S3_SYNC_PROTOCOL_STATE_TABLE)
    if (hasNormalizedS3SyncProtocolStateColumns(columns)) {
        return
    }

    val legacyTable = "${S3_SYNC_PROTOCOL_STATE_TABLE}_legacy_v41"
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` RENAME TO `$legacyTable`")
    createS3SyncProtocolStateTable(db)
    val legacyColumns = db.tableColumns(legacyTable)
    db.execSQL(
        """
        INSERT OR REPLACE INTO `$S3_SYNC_PROTOCOL_STATE_TABLE` (
            `id`,
            `protocol_version`,
            `last_successful_sync_at`,
            `last_fast_sync_at`,
            `last_reconcile_at`,
            `last_full_remote_scan_at`,
            `indexed_local_file_count`,
            `indexed_remote_file_count`,
            `local_mode_fingerprint`,
            `remote_scan_cursor`,
            `scan_epoch`
        )
        SELECT
            ${pickIntExpr(legacyColumns, "id", defaultExpr = S3SyncProtocolStateEntity.SINGLETON_ID.toString())},
            ${pickIntExpr(legacyColumns, "protocol_version", "protocolVersion")},
            ${pickNullableIntExpr(legacyColumns, "last_successful_sync_at", "lastSuccessfulSyncAt")},
            ${pickNullableIntExpr(legacyColumns, "last_fast_sync_at", "lastFastSyncAt")},
            ${pickNullableIntExpr(legacyColumns, "last_reconcile_at", "lastReconcileAt")},
            ${pickNullableIntExpr(legacyColumns, "last_full_remote_scan_at", "lastFullRemoteScanAt")},
            ${pickIntExpr(legacyColumns, "indexed_local_file_count", "indexedLocalFileCount")},
            ${pickIntExpr(legacyColumns, "indexed_remote_file_count", "indexedRemoteFileCount")},
            ${pickNullableTextExpr(legacyColumns, "local_mode_fingerprint", "localModeFingerprint")},
            ${pickNullableTextExpr(legacyColumns, "remote_scan_cursor", "remoteScanCursor")},
            ${pickIntExpr(legacyColumns, "scan_epoch", defaultExpr = "0")}
        FROM `$legacyTable`
        """.trimIndent(),
    )
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
}

private fun hasNormalizedS3SyncProtocolStateColumns(columns: Set<String>): Boolean {
    val hasLegacyManifestColumn = "last_manifest_revision" in columns
    val hasIncrementalColumns =
        "last_fast_sync_at" in columns &&
            "last_reconcile_at" in columns &&
            "last_full_remote_scan_at" in columns
    val hasScanCursorColumns =
        "remote_scan_cursor" in columns &&
            "scan_epoch" in columns
    return !hasLegacyManifestColumn && hasIncrementalColumns && hasScanCursorColumns
}

private fun createMemoVersionTables(db: SupportSQLiteDatabase) {
    createMemoVersionCommitTable(db)
    createMemoVersionBlobTable(db)
    createMemoRevisionTable(db)
    createMemoRevisionAssetTable(db)
}

private fun dropRetiredWorkspaceHistoryTables(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS `workspace_mutation`")
    db.execSQL("DROP TABLE IF EXISTS `workspace_head`")
    db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot_entry`")
    db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot`")
    db.execSQL("DROP TABLE IF EXISTS `snapshot_blob`")
}

private fun createPendingSyncConflictTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `pending_sync_conflict` (
            `backend` TEXT NOT NULL,
            `session_kind` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `payload_json` TEXT NOT NULL,
            PRIMARY KEY(`backend`)
        )
        """.trimIndent(),
    )
}
