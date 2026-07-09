package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun createImageLocationCacheTable(db: SQLiteConnection) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$IMAGE_LOCATION_CACHE_TABLE` (
            `name` TEXT NOT NULL,
            `uri` TEXT NOT NULL,
            PRIMARY KEY(`name`)
        )
        """.trimIndent(),
    )
}
