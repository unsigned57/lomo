package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun createS3SyncMetadataTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_SYNC_METADATA_TABLE` (
            `relative_path` TEXT NOT NULL,
            `remote_path` TEXT NOT NULL,
            `etag` TEXT,
            `remote_last_modified` INTEGER,
            `local_last_modified` INTEGER,
            `last_synced_at` INTEGER NOT NULL,
            `last_resolved_direction` TEXT NOT NULL,
            `last_resolved_reason` TEXT NOT NULL,
            PRIMARY KEY(`relative_path`)
        )
        """.trimIndent(),
    )
}

internal fun createS3SyncProtocolStateTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_SYNC_PROTOCOL_STATE_TABLE` (
            `id` INTEGER NOT NULL,
            `protocol_version` INTEGER NOT NULL,
            `last_manifest_revision` INTEGER,
            `last_successful_sync_at` INTEGER,
            `indexed_local_file_count` INTEGER NOT NULL,
            `indexed_remote_file_count` INTEGER NOT NULL,
            `local_mode_fingerprint` TEXT,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}

internal fun createS3LocalChangeJournalTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$S3_LOCAL_CHANGE_JOURNAL_TABLE` (
            `id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `filename` TEXT NOT NULL,
            `change_type` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE INDEX IF NOT EXISTS `index_${S3_LOCAL_CHANGE_JOURNAL_TABLE}_updated_at`
        ON `$S3_LOCAL_CHANGE_JOURNAL_TABLE` (`updated_at`)
        """.trimIndent(),
    )
}
