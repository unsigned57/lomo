package com.lomo.data.local.dao

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.lomo.data.local.JdbcSQLiteConnection
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.MemoDatabase_Impl
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists

/*
 * Behavior Contract:
 * - Unit under test: MemoSearchDao.getMemosByTagPage production Room query.
 * - Owning layer: data/local DAO.
 * - Priority tier: P0.
 * - Capability: tag-filtered memo pages paginate unique memos after parent/child tag matching.
 *
 * Scenarios:
 * - Given the newest memo has both "project" and "project/mobile" tag rows and an older memo has
 *   "project/mobile", when the "project" tag page is read with limit=2 and offset=0, then two
 *   unique memo ids are returned in timestamp/id order.
 * - Given the same rows, when the "project" tag page is read with limit=2 and offset=1, then the
 *   page starts at the older memo instead of spending the offset on the newest memo's duplicate tag
 *   match.
 *
 * Observable outcomes:
 * - Room executes the production DAO query against SQLite and returns memo ids from persisted rows.
 *
 * TDD proof:
 * - RED proof observed against the same SQLite fixture with a duplicate-producing
 *   Lomo/MemoTagCrossRef JOIN: limit=2, offset=1 returned ["memo-newest", "memo-older"] instead of
 *   ["memo-older"]. Production SQL already used EXISTS when this executable DAO regression was added,
 *   so the first production-DAO run is expected to be GREEN as a coverage-only follow-up.
 *
 * Excludes:
 * - Repository pin merging, Flow invalidation, FTS search, migration history, and app UI behavior.
 */
class MemoSearchDaoSqlRegressionTest : DataFunSpec() {
    private lateinit var databasePath: Path
    private lateinit var driver: TestJdbcSQLiteDriver
    private lateinit var database: MemoDatabase

    init {
        beforeTest {
            databasePath = Files.createTempFile("memo-search-dao-", ".db")
            databasePath.deleteIfExists()
            driver = TestJdbcSQLiteDriver()
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

        test("given parent and child tag rows when tag pages are read then join duplicates do not consume limit or offset") {
            `given parent and child tag rows when tag pages are read then join duplicates do not consume limit or offset`()
        }
    }

    private fun `given parent and child tag rows when tag pages are read then join duplicates do not consume limit or offset`() =
        runTest {
            val newest = memoEntity(id = "memo-newest", timestamp = 300L, tags = "project,project/mobile")
            val older = memoEntity(id = "memo-older", timestamp = 200L, tags = "project/mobile")
            database.memoWriteDao().insertMemos(listOf(newest, older))
            database.memoTagDao().insertTagRefs(
                listOf(
                    MemoTagCrossRefEntity(memoId = newest.id, tag = "project"),
                    MemoTagCrossRefEntity(memoId = newest.id, tag = "project/mobile"),
                    MemoTagCrossRefEntity(memoId = older.id, tag = "project/mobile"),
                ),
            )

            val firstPage =
                database
                    .memoSearchDao()
                    .getMemosByTagPage(tag = "project", tagPrefix = "project/%", limit = 2, offset = 0)
            val offsetPage =
                database
                    .memoSearchDao()
                    .getMemosByTagPage(tag = "project", tagPrefix = "project/%", limit = 2, offset = 1)

            firstPage.map(MemoEntity::id) shouldBe listOf("memo-newest", "memo-older")
            offsetPage.map(MemoEntity::id) shouldBe listOf("memo-older")
        }

    private fun memoEntity(
        id: String,
        timestamp: Long,
        tags: String,
    ): MemoEntity =
        MemoEntity(
            id = id,
            timestamp = timestamp,
            updatedAt = timestamp,
            content = "content for $id",
            searchContent = "content for $id",
            rawContent = "- 10:00 content for $id",
            date = "2026_05_23",
            tags = tags,
            imageUrls = "",
            geoLocation = null,
        )
}

private class TestJdbcSQLiteDriver : SQLiteDriver,
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
