package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun createWebDavLocalFingerprintTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `webdav_local_fingerprint` (
            `path` TEXT NOT NULL,
            `last_modified` INTEGER NOT NULL,
            `size` INTEGER,
            `fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`path`)
        )
        """.trimIndent(),
    )
}

internal fun createWebDavLocalChangeJournalTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `webdav_local_change_journal` (
            `id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `filename` TEXT NOT NULL,
            `change_type` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
}
