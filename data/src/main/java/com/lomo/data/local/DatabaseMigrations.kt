package com.lomo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_18_19: Migration =
    object : Migration(18, 19) {
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
    object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS image_cache")
        }
    }

val MIGRATION_20_21: Migration =
    object : Migration(20, 21) {
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
    object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Normalize schema for users who may have diverged local schemas on v21 builds.
            db.execSQL("DROP TABLE IF EXISTS `MemoTagCrossRef`")

            normalizeMemoTable(db, tableName = "Lomo", withContentIndex = true)
            normalizeMemoTable(db, tableName = "LomoTrash", withContentIndex = false)
            normalizeLocalFileStateTable(db)
            normalizeMemoFileOutboxTable(db)
            rebuildMemoFtsTable(db)
            rebuildMemoTagCrossRefTable(db)
        }
    }

val MIGRATION_22_23: Migration =
    object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`")
        }
    }

val ALL_DATABASE_MIGRATIONS =
    arrayOf(
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
    )

private fun normalizeMemoTable(
    db: SupportSQLiteDatabase,
    tableName: String,
    withContentIndex: Boolean,
) {
    if (!db.tableExists(tableName)) {
        createMemoTable(db, tableName, withContentIndex)
        return
    }

    val legacyTable = "${tableName}_legacy_v22"
    val columns = db.tableColumns(tableName)

    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `$tableName` RENAME TO `$legacyTable`")
    createMemoTable(db, tableName, withContentIndex)

    val idExpr =
        if ("id" in columns) {
            "COALESCE(CAST(`id` AS TEXT), 'legacy_' || rowid)"
        } else {
            "'legacy_' || rowid"
        }
    val timestampExpr = pickIntExpr(columns, "timestamp")
    val contentExpr = pickTextExpr(columns, "content", "rawContent")
    val rawContentExpr = pickTextExpr(columns, "rawContent", "content")
    val dateExpr = pickTextExpr(columns, "date", "dateKey")
    val tagsExpr = pickTextExpr(columns, "tags")
    val imageUrlsExpr = pickTextExpr(columns, "imageUrls")

    db.execSQL(
        """
        INSERT OR REPLACE INTO `$tableName` (
            `id`, `timestamp`, `content`, `rawContent`, `date`, `tags`, `imageUrls`
        )
        SELECT
            $idExpr,
            $timestampExpr,
            $contentExpr,
            $rawContentExpr,
            $dateExpr,
            $tagsExpr,
            $imageUrlsExpr
        FROM `$legacyTable`
        """.trimIndent(),
    )

    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
}

private fun createMemoTable(
    db: SupportSQLiteDatabase,
    tableName: String,
    withContentIndex: Boolean,
) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `$tableName` (
            `id` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `content` TEXT NOT NULL,
            `rawContent` TEXT NOT NULL,
            `date` TEXT NOT NULL,
            `tags` TEXT NOT NULL,
            `imageUrls` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tableName}_timestamp` ON `$tableName` (`timestamp`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tableName}_date` ON `$tableName` (`date`)")
    if (withContentIndex) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_${tableName}_content` ON `$tableName` (`content`)")
    }
}

private fun normalizeLocalFileStateTable(db: SupportSQLiteDatabase) {
    if (!db.tableExists("local_file_state")) {
        createLocalFileStateTable(db)
        return
    }

    val legacyTable = "local_file_state_legacy_v22"
    val columns = db.tableColumns("local_file_state")
    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `local_file_state` RENAME TO `$legacyTable`")
    createLocalFileStateTable(db)

    val filenameExpr = pickTextExpr(columns, "filename")
    val isTrashExpr = pickIntExpr(columns, "isTrash", "is_trash")
    val safUriExpr = pickNullableTextExpr(columns, "saf_uri", "safUri")
    val lastKnownExpr = pickIntExpr(columns, "last_known_modified_time", "lastKnownModifiedTime", "lastModified")

    db.execSQL(
        """
        INSERT OR REPLACE INTO `local_file_state` (
            `filename`, `isTrash`, `saf_uri`, `last_known_modified_time`
        )
        SELECT
            $filenameExpr,
            $isTrashExpr,
            $safUriExpr,
            $lastKnownExpr
        FROM `$legacyTable`
        """.trimIndent(),
    )

    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
}

private fun createLocalFileStateTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `local_file_state` (
            `filename` TEXT NOT NULL,
            `isTrash` INTEGER NOT NULL,
            `saf_uri` TEXT,
            `last_known_modified_time` INTEGER NOT NULL,
            PRIMARY KEY(`filename`, `isTrash`)
        )
        """.trimIndent(),
    )
}

private fun normalizeMemoFileOutboxTable(db: SupportSQLiteDatabase) {
    if (!db.tableExists("MemoFileOutbox")) {
        createMemoFileOutboxTable(db)
        return
    }

    val legacyTable = "MemoFileOutbox_legacy_v22"
    val columns = db.tableColumns("MemoFileOutbox")
    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
    db.execSQL("ALTER TABLE `MemoFileOutbox` RENAME TO `$legacyTable`")
    createMemoFileOutboxTable(db)

    val nowExpr = "(CAST(strftime('%s','now') AS INTEGER) * 1000)"
    val idExpr = pickNullableIntExpr(columns, "id")
    val operationExpr = pickTextExpr(columns, "operation", defaultExpr = "'UPDATE'")
    val memoIdExpr = pickTextExpr(columns, "memoId", "id")
    val memoDateExpr = pickTextExpr(columns, "memoDate", "date")
    val memoTimestampExpr = pickIntExpr(columns, "memoTimestamp", "timestamp")
    val memoRawContentExpr = pickTextExpr(columns, "memoRawContent", "rawContent", "content")
    val newContentExpr = pickNullableTextExpr(columns, "newContent")
    val createRawContentExpr = pickNullableTextExpr(columns, "createRawContent")
    val createdAtExpr = pickIntExpr(columns, "createdAt", defaultExpr = nowExpr)
    val updatedAtExpr = pickIntExpr(columns, "updatedAt", "createdAt", defaultExpr = nowExpr)
    val retryCountExpr = pickIntExpr(columns, "retryCount")
    val lastErrorExpr = pickNullableTextExpr(columns, "lastError")

    db.execSQL(
        """
        INSERT OR REPLACE INTO `MemoFileOutbox` (
            `id`,
            `operation`,
            `memoId`,
            `memoDate`,
            `memoTimestamp`,
            `memoRawContent`,
            `newContent`,
            `createRawContent`,
            `createdAt`,
            `updatedAt`,
            `retryCount`,
            `lastError`
        )
        SELECT
            $idExpr,
            $operationExpr,
            $memoIdExpr,
            $memoDateExpr,
            $memoTimestampExpr,
            $memoRawContentExpr,
            $newContentExpr,
            $createRawContentExpr,
            $createdAtExpr,
            $updatedAtExpr,
            $retryCountExpr,
            $lastErrorExpr
        FROM `$legacyTable`
        """.trimIndent(),
    )

    db.execSQL("DROP TABLE IF EXISTS `$legacyTable`")
}

private fun createMemoFileOutboxTable(db: SupportSQLiteDatabase) {
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

private fun rebuildMemoFtsTable(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS `lomo_fts`")
    db.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `lomo_fts`
        USING FTS4(`memoId` TEXT NOT NULL, `content` TEXT NOT NULL, tokenize=unicode61)
        """.trimIndent(),
    )
    if (db.tableExists("Lomo")) {
        db.execSQL(
            """
            INSERT INTO `lomo_fts` (`memoId`, `content`)
            SELECT `id`, `content`
            FROM `Lomo`
            """.trimIndent(),
        )
    }
}

private fun rebuildMemoTagCrossRefTable(db: SupportSQLiteDatabase) {
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

private fun SupportSQLiteDatabase.tableExists(tableName: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$tableName' LIMIT 1").use { cursor ->
        cursor.moveToFirst()
    }

private fun SupportSQLiteDatabase.tableColumns(tableName: String): Set<String> =
    query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val columns = linkedSetOf<String>()
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0) {
                cursor.getString(nameIndex)?.let(columns::add)
            }
        }
        columns
    }

private fun pickTextExpr(
    columns: Set<String>,
    vararg candidates: String,
    defaultExpr: String = "''",
): String {
    val column = candidates.firstOrNull { it in columns } ?: return defaultExpr
    return "COALESCE(CAST(`$column` AS TEXT), $defaultExpr)"
}

private fun pickNullableTextExpr(
    columns: Set<String>,
    vararg candidates: String,
): String {
    val column = candidates.firstOrNull { it in columns } ?: return "NULL"
    return "CAST(`$column` AS TEXT)"
}

private fun pickIntExpr(
    columns: Set<String>,
    vararg candidates: String,
    defaultExpr: String = "0",
): String {
    val column = candidates.firstOrNull { it in columns } ?: return defaultExpr
    return "COALESCE(CAST(`$column` AS INTEGER), $defaultExpr)"
}

private fun pickNullableIntExpr(
    columns: Set<String>,
    vararg candidates: String,
): String {
    val column = candidates.firstOrNull { it in columns } ?: return "NULL"
    return "CAST(`$column` AS INTEGER)"
}
