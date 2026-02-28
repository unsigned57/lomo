package com.lomo.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
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
}
