package com.lomo.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: PlaintextDatabaseVersionReader
 * - Behavior focus: pre-open schema inspection for plaintext databases must read `PRAGMA user_version`
 *   through the bundled SQLite driver so opening an existing FTS5 database never falls back to framework SQLite.
 * - Observable outcomes: returned version number and bundled-driver call sequence.
 * - Red phase: Fails before the fix because DatabaseTransitionStrategy reads user_version through
 *   android.database.sqlite.SQLiteDatabase instead of the bundled driver.
 * - Excludes: real filesystem I/O, bundled native library loading, and migration policy decisions.
 */
class PlaintextDatabaseVersionReaderTest {
    @Test
    fun `readUserVersion queries pragma through sqlite driver connection`() {
        val driver = mockk<SQLiteDriver>()
        val connection = mockk<SQLiteConnection>()
        val statement = mockk<SQLiteStatement>()
        val databaseFile = File("/tmp/lomo.db")

        every { driver.open(databaseFile.path) } returns connection
        every { connection.prepare("PRAGMA user_version") } returns statement
        every { statement.step() } returns true
        every { statement.getLong(0) } returns 51L
        every { statement.close() } returns Unit
        every { connection.close() } returns Unit

        val result = PlaintextDatabaseVersionReader.readUserVersion(databaseFile, driver)

        assertEquals(51, result)
        verifySequence {
            driver.open(databaseFile.path)
            connection.prepare("PRAGMA user_version")
            statement.step()
            statement.getLong(0)
            statement.close()
            connection.close()
        }
    }
}
