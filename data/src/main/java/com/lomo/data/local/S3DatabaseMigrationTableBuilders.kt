package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

// These S3 builders are the canonical current-schema definition for manually managed tables. They are shared
// by incremental migrations and consolidation, so any added column or index here also changes direct upgrades.
internal fun createS3SyncMetadataTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_SYNC_METADATA_TABLE` (
            `workspace_generation` TEXT NOT NULL,
            `relative_path` TEXT NOT NULL,
            `remote_path` TEXT NOT NULL,
            `etag` TEXT,
            `remote_last_modified` INTEGER,
            `local_last_modified` INTEGER,
            `local_size` INTEGER,
            `remote_size` INTEGER,
            `local_fingerprint` TEXT,
            `last_synced_at` INTEGER NOT NULL,
            `last_resolved_direction` TEXT NOT NULL,
            `last_resolved_reason` TEXT NOT NULL,
            PRIMARY KEY(`workspace_generation`, `relative_path`)
        )
        """.trimIndent(),
    )
}

internal fun createS3RemoteIndexTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_REMOTE_INDEX_TABLE` (
            `workspace_generation` TEXT NOT NULL,
            `relative_path` TEXT NOT NULL,
            `remote_path` TEXT NOT NULL,
            `etag` TEXT,
            `remote_last_modified` INTEGER,
            `size` INTEGER,
            `last_seen_at` INTEGER NOT NULL,
            `last_verified_at` INTEGER,
            `scan_bucket` TEXT NOT NULL,
            `scan_priority` INTEGER NOT NULL,
            `dirty_suspect` INTEGER NOT NULL,
            `missing_on_last_scan` INTEGER NOT NULL,
            `scan_epoch` INTEGER NOT NULL,
            PRIMARY KEY(`workspace_generation`, `relative_path`)
        )
        """.trimIndent(),
    )
    ensureS3RemoteIndexSupportingIndex(db)
}

internal fun ensureS3RemoteIndexSupportingIndex(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_${S3_REMOTE_INDEX_TABLE}_scan_priority_last_verified_at`
        ON `$S3_REMOTE_INDEX_TABLE` (`scan_priority`, `last_verified_at`)
        """.trimIndent(),
    )
}

internal fun createS3RemoteShardStateTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_REMOTE_SHARD_STATE_TABLE` (
            `workspace_generation` TEXT NOT NULL,
            `bucket_id` TEXT NOT NULL,
            `relative_prefix` TEXT,
            `last_scanned_at` INTEGER NOT NULL,
            `last_object_count` INTEGER NOT NULL,
            `last_duration_ms` INTEGER NOT NULL,
            `last_change_count` INTEGER NOT NULL,
            PRIMARY KEY(`workspace_generation`, `bucket_id`)
        )
        """.trimIndent(),
    )
}

internal fun addS3RemoteShardTelemetryColumns(db: SQLiteConnection) {
    val columns = db.tableColumns(S3_REMOTE_SHARD_STATE_TABLE)
    if ("idle_scan_streak" !in columns) {
        db.execSQL(
            "ALTER TABLE `$S3_REMOTE_SHARD_STATE_TABLE` ADD COLUMN `idle_scan_streak` INTEGER NOT NULL DEFAULT 0",
        )
    }
    if ("last_verification_attempt_count" !in columns) {
        db.execSQL(
            """
            ALTER TABLE `$S3_REMOTE_SHARD_STATE_TABLE`
            ADD COLUMN `last_verification_attempt_count` INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
    if ("last_verification_failure_count" !in columns) {
        db.execSQL(
            """
            ALTER TABLE `$S3_REMOTE_SHARD_STATE_TABLE`
            ADD COLUMN `last_verification_failure_count` INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),
        )
    }
}

internal fun createS3SyncProtocolStateTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_SYNC_PROTOCOL_STATE_TABLE` (
            `workspace_generation` TEXT NOT NULL,
            `id` INTEGER NOT NULL,
            `protocol_version` INTEGER NOT NULL,
            `last_successful_sync_at` INTEGER,
            `last_fast_sync_at` INTEGER,
            `last_reconcile_at` INTEGER,
            `last_full_remote_scan_at` INTEGER,
            `indexed_local_file_count` INTEGER NOT NULL,
            `indexed_remote_file_count` INTEGER NOT NULL,
            `local_mode_fingerprint` TEXT,
            `remote_scan_cursor` TEXT,
            `scan_epoch` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`workspace_generation`, `id`)
        )
        """.trimIndent(),
    )
}

internal fun createS3LocalChangeJournalTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_LOCAL_CHANGE_JOURNAL_TABLE` (
            `workspace_generation` TEXT NOT NULL,
            `id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `filename` TEXT NOT NULL,
            `change_type` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`workspace_generation`, `id`)
        )
        """.trimIndent(),
    )
    ensureS3LocalChangeJournalIndex(db)
}

internal fun ensureS3LocalChangeJournalIndex(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_${S3_LOCAL_CHANGE_JOURNAL_TABLE}_updated_at`
        ON `$S3_LOCAL_CHANGE_JOURNAL_TABLE` (`updated_at`)
        """.trimIndent(),
    )
}
