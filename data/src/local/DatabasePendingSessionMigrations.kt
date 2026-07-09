package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun createMemoVersionTables(db: SQLiteConnection) {
    createMemoVersionCommitTable(db)
    createMemoVersionBlobTable(db)
    createMemoRevisionTable(db)
    createMemoRevisionAssetTable(db)
}

internal fun dropRetiredWorkspaceHistoryTables(db: SQLiteConnection) {
    db.execSQL("DROP TABLE IF EXISTS `workspace_mutation`")
    db.execSQL("DROP TABLE IF EXISTS `workspace_head`")
    db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot_entry`")
    db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot`")
    db.execSQL("DROP TABLE IF EXISTS `snapshot_blob`")
}

internal fun createPendingSyncConflictTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `pending_sync_conflict` (
            `workspace_generation` TEXT NOT NULL,
            `backend` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `payload_json` TEXT NOT NULL,
            PRIMARY KEY(`workspace_generation`, `backend`)
        )
        """.trimIndent(),
    )
}

private fun createUnscopedPendingSyncConflictTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `pending_sync_conflict` (
            `backend` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `payload_json` TEXT NOT NULL,
            PRIMARY KEY(`backend`)
        )
        """.trimIndent(),
    )
}

internal fun createLegacyPendingSyncConflictTable(db: SQLiteConnection) {
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

internal fun createPendingSyncReviewTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `pending_sync_review` (
            `workspace_generation` TEXT NOT NULL,
            `backend` TEXT NOT NULL,
            `review_kind` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `payload_json` TEXT NOT NULL,
            PRIMARY KEY(`workspace_generation`, `backend`)
        )
        """.trimIndent(),
    )
}

private fun createUnscopedPendingSyncReviewTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `pending_sync_review` (
            `backend` TEXT NOT NULL,
            `review_kind` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `payload_json` TEXT NOT NULL,
            PRIMARY KEY(`backend`)
        )
        """.trimIndent(),
    )
}

internal fun splitPendingSyncReviewTable(db: SQLiteConnection) {
    createUnscopedPendingSyncReviewTable(db)
    if (!db.tableExists("pending_sync_conflict")) {
        createUnscopedPendingSyncConflictTable(db)
        return
    }
    val columns = db.tableColumns("pending_sync_conflict")
    if ("session_kind" !in columns) {
        return
    }
    db.execSQL(
        """
        INSERT OR REPLACE INTO `pending_sync_review` (`backend`, `review_kind`, `timestamp`, `payload_json`)
        SELECT `backend`, `session_kind`, `timestamp`, `payload_json`
        FROM `pending_sync_conflict`
        WHERE `session_kind` != 'CONFLICT'
        """.trimIndent(),
    )
    db.execSQL("ALTER TABLE `pending_sync_conflict` RENAME TO `pending_sync_conflict_legacy_v56`")
    createUnscopedPendingSyncConflictTable(db)
    db.execSQL(
        """
        INSERT OR REPLACE INTO `pending_sync_conflict` (`backend`, `timestamp`, `payload_json`)
        SELECT `backend`, `timestamp`, `payload_json`
        FROM `pending_sync_conflict_legacy_v56`
        WHERE `session_kind` = 'CONFLICT'
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE IF EXISTS `pending_sync_conflict_legacy_v56`")
}

internal fun addGeoLocationColumn(db: SQLiteConnection) {
    val columns = db.tableColumns("Lomo")
    if ("geoLocation" !in columns) {
        db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `geoLocation` TEXT DEFAULT NULL")
    }
}
