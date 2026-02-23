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
