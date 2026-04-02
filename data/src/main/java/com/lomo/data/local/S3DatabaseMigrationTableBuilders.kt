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
