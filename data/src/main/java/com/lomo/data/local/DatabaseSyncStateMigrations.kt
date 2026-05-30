package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import com.lomo.data.local.entity.S3SyncProtocolStateEntity

internal fun normalizeS3SyncMetadataTable(db: SQLiteConnection) {
    if (!db.tableExists(S3_SYNC_METADATA_TABLE)) {
        createS3SyncMetadataTable(db)
        return
    }

    val columns = db.tableColumns(S3_SYNC_METADATA_TABLE)
    if ("workspace_generation" !in columns) {
        dropAndCreateS3SyncMetadataTable(db)
        return
    }
    if (hasNormalizedS3SyncMetadataColumns(columns)) {
        return
    }

    val legacyTable = "${S3_SYNC_METADATA_TABLE}_legacy_v45"
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$S3_SYNC_METADATA_TABLE` RENAME TO `$legacyTable`")
    db.dropExplicitIndices(legacyTable)
    createS3SyncMetadataTable(db)
    val legacyColumns = db.tableColumns(legacyTable)
    db.execSQL(
        """
        INSERT OR REPLACE INTO `$S3_SYNC_METADATA_TABLE` (
            `workspace_generation`,
            `relative_path`,
            `remote_path`,
            `etag`,
            `remote_last_modified`,
            `local_last_modified`,
            `local_size`,
            `remote_size`,
            `local_fingerprint`,
            `last_synced_at`,
            `last_resolved_direction`,
            `last_resolved_reason`
        )
        SELECT
            ${pickTextExpr(legacyColumns, "workspace_generation")},
            ${pickTextExpr(legacyColumns, "relative_path", "relativePath")},
            ${pickTextExpr(legacyColumns, "remote_path", "remotePath")},
            ${pickNullableTextExpr(legacyColumns, "etag")},
            ${pickNullableIntExpr(legacyColumns, "remote_last_modified", "remoteLastModified")},
            ${pickNullableIntExpr(legacyColumns, "local_last_modified", "localLastModified")},
            ${pickNullableIntExpr(legacyColumns, "local_size", "localSize")},
            ${pickNullableIntExpr(legacyColumns, "remote_size", "remoteSize")},
            ${pickNullableTextExpr(legacyColumns, "local_fingerprint", "localFingerprint")},
            ${pickIntExpr(legacyColumns, "last_synced_at", "lastSyncedAt")},
            ${pickTextExpr(legacyColumns, "last_resolved_direction", "lastResolvedDirection")},
            ${pickTextExpr(legacyColumns, "last_resolved_reason", "lastResolvedReason")}
        FROM `$legacyTable`
        """.trimIndent(),
    )
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
}

private fun hasNormalizedS3SyncMetadataColumns(columns: Set<String>): Boolean =
    "local_size" in columns &&
        "remote_size" in columns &&
        "local_fingerprint" in columns

internal fun addS3SyncMetadataPersistenceColumns(db: SQLiteConnection) {
    addColumnIfMissing(db, S3_SYNC_METADATA_TABLE, "local_size", "`local_size` INTEGER")
    addColumnIfMissing(db, S3_SYNC_METADATA_TABLE, "remote_size", "`remote_size` INTEGER")
    addColumnIfMissing(db, S3_SYNC_METADATA_TABLE, "local_fingerprint", "`local_fingerprint` TEXT")
}

internal fun normalizeWebDavSyncMetadataTable(db: SQLiteConnection) {
    if (!db.tableExists(WEBDAV_SYNC_METADATA_TABLE)) {
        createWebDavSyncMetadataTable(db)
        return
    }

    val columns = db.tableColumns(WEBDAV_SYNC_METADATA_TABLE)
    if ("workspace_generation" !in columns) {
        dropAndCreateWebDavSyncMetadataTable(db)
        return
    }
    if ("local_fingerprint" in columns) {
        return
    }

    val legacyTable = "${WEBDAV_SYNC_METADATA_TABLE}_legacy_v46"
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$WEBDAV_SYNC_METADATA_TABLE` RENAME TO `$legacyTable`")
    db.dropExplicitIndices(legacyTable)
    createWebDavSyncMetadataTable(db)
    val legacyColumns = db.tableColumns(legacyTable)
    db.execSQL(
        """
        INSERT OR REPLACE INTO `$WEBDAV_SYNC_METADATA_TABLE` (
            `workspace_generation`,
            `relative_path`,
            `remote_path`,
            `etag`,
            `remote_last_modified`,
            `local_last_modified`,
            `local_fingerprint`,
            `last_synced_at`,
            `last_resolved_direction`,
            `last_resolved_reason`
        )
        SELECT
            ${pickTextExpr(legacyColumns, "workspace_generation")},
            ${pickTextExpr(legacyColumns, "relative_path", "relativePath")},
            ${pickTextExpr(legacyColumns, "remote_path", "remotePath")},
            ${pickNullableTextExpr(legacyColumns, "etag")},
            ${pickNullableIntExpr(legacyColumns, "remote_last_modified", "remoteLastModified")},
            ${pickNullableIntExpr(legacyColumns, "local_last_modified", "localLastModified")},
            ${pickNullableTextExpr(legacyColumns, "local_fingerprint", "localFingerprint")},
            ${pickIntExpr(legacyColumns, "last_synced_at", "lastSyncedAt")},
            ${pickTextExpr(legacyColumns, "last_resolved_direction", "lastResolvedDirection")},
            ${pickTextExpr(legacyColumns, "last_resolved_reason", "lastResolvedReason")}
        FROM `$legacyTable`
        """.trimIndent(),
    )
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
}

internal fun normalizeS3SyncProtocolStateTable(db: SQLiteConnection) {
    if (!db.tableExists(S3_SYNC_PROTOCOL_STATE_TABLE)) {
        createS3SyncProtocolStateTable(db)
        return
    }
    val columns = db.tableColumns(S3_SYNC_PROTOCOL_STATE_TABLE)
    if ("workspace_generation" !in columns) {
        dropAndCreateS3SyncProtocolStateTable(db)
        return
    }
    if (hasNormalizedS3SyncProtocolStateColumns(columns)) {
        return
    }

    val legacyTable = "${S3_SYNC_PROTOCOL_STATE_TABLE}_legacy_v41"
    db.execSQL("$DROP_TABLE_IF_EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$S3_SYNC_PROTOCOL_STATE_TABLE` RENAME TO `$legacyTable`")
    db.dropExplicitIndices(legacyTable)
    createS3SyncProtocolStateTable(db)
    val legacyColumns = db.tableColumns(legacyTable)
    db.execSQL(
        """
        INSERT OR REPLACE INTO `$S3_SYNC_PROTOCOL_STATE_TABLE` (
            `workspace_generation`,
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
            ${pickTextExpr(legacyColumns, "workspace_generation")},
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

internal fun addS3SyncProtocolIncrementalColumns(db: SQLiteConnection) {
    addColumnIfMissing(db, S3_SYNC_PROTOCOL_STATE_TABLE, "last_fast_sync_at", "`last_fast_sync_at` INTEGER")
    addColumnIfMissing(db, S3_SYNC_PROTOCOL_STATE_TABLE, "last_reconcile_at", "`last_reconcile_at` INTEGER")
    addColumnIfMissing(
        db,
        S3_SYNC_PROTOCOL_STATE_TABLE,
        "last_full_remote_scan_at",
        "`last_full_remote_scan_at` INTEGER",
    )
    addColumnIfMissing(db, S3_SYNC_PROTOCOL_STATE_TABLE, "remote_scan_cursor", "`remote_scan_cursor` TEXT")
    addColumnIfMissing(
        db,
        S3_SYNC_PROTOCOL_STATE_TABLE,
        "scan_epoch",
        "`scan_epoch` INTEGER NOT NULL DEFAULT 0",
    )
}

internal fun addColumnIfMissing(
    db: SQLiteConnection,
    tableName: String,
    columnName: String,
    columnDefinition: String,
) {
    if (columnName !in db.tableColumns(tableName)) {
        db.execSQL("ALTER TABLE `$tableName` ADD COLUMN $columnDefinition")
    }
}
