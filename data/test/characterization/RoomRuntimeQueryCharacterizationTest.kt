package com.lomo.data.characterization

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.lomo.data.local.JdbcSQLiteConnection
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.MemoDatabase_Impl
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: production MemoDatabase + MemoWriteDao/MemoDao query path
 * - Owning layer: data (test-only characterization)
 * - Capability: open Room on real SQLite, insert a memo, query it back (runtime query behavior).
 *
 * Scenarios:
 * - Given an empty MemoDatabase, when one memo is inserted and queried by id, then content matches.
 * - Given the same row, when getMemoCountSync runs, then count is 1.
 *
 * Observable outcomes: persisted id/content and count from production DAO SQL.
 * TDD proof: RED if Room open/insert/query path regresses.
 * Excludes: UI, FTS full corpus, migration history (covered by migration integration tests).
 */
class RoomRuntimeQueryCharacterizationTest : DataFunSpec() {
    private lateinit var databasePath: Path
    private lateinit var driver: CharacterizationJdbcDriver
    private lateinit var database: MemoDatabase

    init {
        beforeTest {
            databasePath = Files.createTempFile("room-runtime-char-", ".db")
            databasePath.deleteIfExists()
            driver = CharacterizationJdbcDriver()
            database =
                Room
                    .databaseBuilder<MemoDatabase>(databasePath.toAbsolutePath().toString()) {
                        MemoDatabase_Impl()
                    }.setDriver(driver)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .build()
        }

        afterTest {
            database.close()
            driver.close()
            databasePath.deleteIfExists()
            databasePath.resolveSibling("${databasePath.fileName}-wal").deleteIfExists()
            databasePath.resolveSibling("${databasePath.fileName}-shm").deleteIfExists()
        }

        test("given room database when memo inserted then query by id and count observe row") {
            runTest {
                val memo =
                    MemoEntity(
                        id = "char-memo-1",
                        timestamp = 1_700_000_000_000L,
                        updatedAt = 1_700_000_000_000L,
                        content = "characterization content #tag",
                        searchContent = "characterization content #tag",
                        rawContent = "- 10:00:00 characterization content #tag",
                        date = "2024-06-01",
                        tags = "tag",
                        imageUrls = "",
                        geoLocation = null,
                    )
                database.memoWriteDao().insertMemos(listOf(memo))
                val loaded = database.memoDao().getMemo("char-memo-1")
                loaded?.id shouldBe "char-memo-1"
                loaded?.content shouldBe "characterization content #tag"
                database.memoDao().getMemoCountSync() shouldBe 1
            }
        }
    }
}

private class CharacterizationJdbcDriver :
    SQLiteDriver,
    AutoCloseable {
    private val connections = mutableListOf<Connection>()

    override fun open(fileName: String): SQLiteConnection {
        Class.forName("org.sqlite.JDBC")
        return JdbcSQLiteConnection(
            DriverManager.getConnection("jdbc:sqlite:$fileName").also(connections::add),
        )
    }

    override fun close() {
        connections.forEach(Connection::close)
        connections.clear()
    }
}
