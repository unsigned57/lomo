package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun createWebDavLocalFingerprintTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `webdav_local_fingerprint` (
            `workspace_generation` TEXT NOT NULL,
            `path` TEXT NOT NULL,
            `last_modified` INTEGER NOT NULL,
            `size` INTEGER,
            `fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`workspace_generation`, `path`)
        )
        """.trimIndent(),
    )
}

internal fun createWebDavLocalChangeJournalTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `webdav_local_change_journal` (
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
}
