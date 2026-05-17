/*
 * Test Contract:
 * - Unit under test: PlaintextDatabaseVersionReader
 * - Behavior focus: extraction of database version from SQL files/streams.
 * - Observable outcomes: correct version identification, error handling for malformed input.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: SQLite execution, Room internals.
 */
package com.lomo.data.local


import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import java.io.File
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

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
class PlaintextDatabaseVersionReaderTest : DataFunSpec() {
    init {
        test("readUserVersion queries pragma through sqlite driver connection") { `readUserVersion queries pragma through sqlite driver connection`() }

        test("inspect queries user version and quick check through sqlite driver connection") { `inspect queries user version and quick check through sqlite driver connection`() }
    }


    private fun `readUserVersion queries pragma through sqlite driver connection`() {
        val driver = mockk<SQLiteDriver>()
        val connection = mockk<SQLiteConnection>()
        val userVersionStatement = mockk<SQLiteStatement>()
        val quickCheckStatement = mockk<SQLiteStatement>()
        val databaseFile = File("/tmp/lomo.db")

        every { driver.open(databaseFile.path) } returns connection
        every { connection.prepare("PRAGMA user_version") } returns userVersionStatement
        every { connection.prepare("PRAGMA quick_check(1)") } returns quickCheckStatement
        every { userVersionStatement.step() } returns true
        every { userVersionStatement.getLong(0) } returns 51L
        every { userVersionStatement.close() } returns Unit
        every { quickCheckStatement.step() } returns true
        every { quickCheckStatement.getText(0) } returns "ok"
        every { quickCheckStatement.close() } returns Unit
        every { connection.close() } returns Unit

        val result = PlaintextDatabaseVersionReader.readUserVersion(databaseFile, driver)

        result shouldBe 51
        verifySequence {
            driver.open(databaseFile.path)
            connection.prepare("PRAGMA user_version")
            userVersionStatement.step()
            userVersionStatement.getLong(0)
            userVersionStatement.close()
            connection.prepare("PRAGMA quick_check(1)")
            quickCheckStatement.step()
            quickCheckStatement.getText(0)
            quickCheckStatement.close()
            connection.close()
        }
    }

    private fun `inspect queries user version and quick check through sqlite driver connection`() {
        val driver = mockk<SQLiteDriver>()
        val connection = mockk<SQLiteConnection>()
        val userVersionStatement = mockk<SQLiteStatement>()
        val quickCheckStatement = mockk<SQLiteStatement>()
        val databaseFile = File("/tmp/lomo.db")

        every { driver.open(databaseFile.path) } returns connection
        every { connection.prepare("PRAGMA user_version") } returns userVersionStatement
        every { connection.prepare("PRAGMA quick_check(1)") } returns quickCheckStatement
        every { userVersionStatement.step() } returns true
        every { userVersionStatement.getLong(0) } returns 51L
        every { userVersionStatement.close() } returns Unit
        every { quickCheckStatement.step() } returns true
        every { quickCheckStatement.getText(0) } returns "ok"
        every { quickCheckStatement.close() } returns Unit
        every { connection.close() } returns Unit

        val result = PlaintextDatabaseVersionReader.inspect(databaseFile, driver)

        result.userVersion shouldBe 51
        (result.quickCheckPassed).shouldBeTrue()
        verifySequence {
            driver.open(databaseFile.path)
            connection.prepare("PRAGMA user_version")
            userVersionStatement.step()
            userVersionStatement.getLong(0)
            userVersionStatement.close()
            connection.prepare("PRAGMA quick_check(1)")
            quickCheckStatement.step()
            quickCheckStatement.getText(0)
            quickCheckStatement.close()
            connection.close()
        }
    }
}
