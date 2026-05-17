/*
 * Test Contract:
 * - Unit under test: DatabaseTransitionStrategy
 * - Behavior focus: file-based database transitions with real file system interactions.
 * - Observable outcomes: successful file moves/copies, backup creation, state consistency.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: SQLite schema logic, Room internals.
 */
package com.lomo.data.local


import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.sql.DriverManager
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

class DatabaseTransitionStrategyRealFileTest : DataFunSpec() {
    init {
        test("prepareBeforeOpen keeps supported real sqlite database file") { `prepareBeforeOpen keeps supported real sqlite database file`() }

        test("prepareBeforeOpen deletes unreachable real sqlite database file") { `prepareBeforeOpen deletes unreachable real sqlite database file`() }

        test("prepareBeforeOpen deletes invalid header database file") { `prepareBeforeOpen deletes invalid header database file`() }
    }


    private val migrationEdges = ALL_DATABASE_MIGRATIONS.map { it.startVersion to it.endVersion }

    private fun `prepareBeforeOpen keeps supported real sqlite database file`() {
        val databaseFile = createSQLiteDatabaseFile("supported.db", userVersion = 44)
        val context = mockContext(databaseFile)
        val inspection = inspectWithJdbc(databaseFile)

        inspection.userVersion shouldBe 44
        (inspection.quickCheckPassed).shouldBeTrue()

        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = MEMO_DATABASE_VERSION,
            databaseName = databaseFile.name,
            migrationEdges = migrationEdges,
            inspectDatabase = ::inspectWithJdbc,
        )

        (databaseFile.exists()).shouldBeTrue()
        verify(exactly = 0) { context.deleteDatabase(databaseFile.name) }
    }

    private fun `prepareBeforeOpen deletes unreachable real sqlite database file`() {
        val databaseFile = createSQLiteDatabaseFile("unsupported.db", userVersion = 31)
        val context = mockContext(databaseFile)

        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = MEMO_DATABASE_VERSION,
            databaseName = databaseFile.name,
            migrationEdges = migrationEdges,
            inspectDatabase = ::inspectWithJdbc,
        )

        (databaseFile.exists()).shouldBeFalse()
        verify(exactly = 1) { context.deleteDatabase(databaseFile.name) }
    }

    private fun `prepareBeforeOpen deletes invalid header database file`() {
        val databaseFile = Files.createTempFile("invalid-header-", ".db").toFile()
        databaseFile.writeText("not a sqlite database")
        val context = mockContext(databaseFile)

        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = MEMO_DATABASE_VERSION,
            databaseName = databaseFile.name,
            migrationEdges = migrationEdges,
        )

        (databaseFile.exists()).shouldBeFalse()
        verify(exactly = 1) { context.deleteDatabase(databaseFile.name) }
    }

    private fun createSQLiteDatabaseFile(
        fileName: String,
        userVersion: Int,
    ) = Files.createTempFile(fileName.removeSuffix(".db"), ".db").toFile().also { databaseFile ->
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS `probe` (`id` INTEGER PRIMARY KEY)")
                statement.execute("PRAGMA user_version = $userVersion")
            }
        }
    }

    private fun inspectWithJdbc(databaseFile: java.io.File): PlaintextDatabaseInspection =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
            val userVersion =
                connection.createStatement().executeQuery("PRAGMA user_version").use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else -1
                }
            val quickCheckPassed =
                connection.createStatement().executeQuery("PRAGMA quick_check(1)").use { resultSet ->
                    resultSet.next() && resultSet.getString(1).trim() == "ok"
                }
            PlaintextDatabaseInspection(
                userVersion = userVersion,
                quickCheckPassed = quickCheckPassed,
            )
        }

    private fun mockContext(databaseFile: java.io.File): Context =
        mockk(relaxed = true) {
            every { getDatabasePath(databaseFile.name) } returns databaseFile
            every { deleteDatabase(databaseFile.name) } answers {
                databaseFile.delete()
            }
        }
}
