package com.lomo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import org.junit.Test

class DatabaseMigrationsTest {
    @Test
    fun `migration 22 to 23 drops legacy Lomo content index`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_22_23.migrate(db)

        verify(exactly = 1) {
            db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`")
        }
    }

    @Test
    fun `migration 23 to 24 adds and backfills updatedAt`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers {
            val sql = args[0] as String
            when {
                sql.contains("sqlite_master") -> mockCursor(true)
                sql.contains("PRAGMA table_info(`Lomo`)") -> mockColumnsCursor(setOf("id", "timestamp"))
                sql.contains("PRAGMA table_info(`LomoTrash`)") -> mockColumnsCursor(setOf("id", "timestamp"))
                else -> mockCursor(false)
            }
        }

        MIGRATION_23_24.migrate(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        }
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `LomoTrash` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        }
        verify(exactly = 1) {
            db.execSQL("UPDATE `Lomo` SET `updatedAt` = `timestamp` WHERE `updatedAt` <= 0")
        }
        verify(exactly = 1) {
            db.execSQL("UPDATE `LomoTrash` SET `updatedAt` = `timestamp` WHERE `updatedAt` <= 0")
        }
    }

    private fun mockCursor(hasRow: Boolean): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns hasRow
        every { cursor.close() } returns Unit
        return cursor
    }

    private fun mockColumnsCursor(columns: Set<String>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        val rows = columns.toList()
        var index = -1
        every { cursor.getColumnIndex("name") } returns 0
        every { cursor.moveToNext() } answers {
            index += 1
            index < rows.size
        }
        every { cursor.getString(0) } answers { rows[index] }
        every { cursor.close() } returns Unit
        return cursor
    }
}
