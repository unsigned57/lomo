package com.lomo.data.local.dao

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.lomo.data.local.JdbcSQLiteConnection
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.MemoDatabase_Impl
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoPinEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.usecase.MemoContentAnalyzer
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists

/*
 * Behavior Contract:
 * - Unit under test: DefaultMainListDao daily review candidate production Room SQL.
 * - Owning layer: data/local DAO.
 * - Priority tier: P0.
 * - Capability: daily review candidate pages keep a stable high-water boundary and keyset order.
 *
 * Scenarios:
 * - Given pinned and unpinned memos across equal and different timestamps and ids, when the first
 *   candidate page is read, then rows are ordered by isPinned DESC, timestamp DESC, id DESC.
 * - Given a captured max rowid, when a newer head memo is inserted after the boundary, then the
 *   first candidate page excludes that new row.
 * - Given a cursor on a pinned row that shares its timestamp with lower ids, when page 2 is read,
 *   then same pinned-status and same-timestamp rows continue through the id keyset branch before
 *   later groups are returned.
 * - Given a captured max rowid, when the candidate count is read after inserting a newer head memo,
 *   then the count excludes rows inserted after the boundary.
 * - Given content-flag filters for analyzer-supported memo syntax, when active query counts are
 *   read, then todo/attachment/url predicates use persisted analyzer projections rather than
 *   handwritten content LIKE approximations.
 * Observable outcomes:
 * - Room executes the production DAO queries against SQLite and returns persisted row ids in the
 *   expected order/count.
 *
 * TDD proof:
 * - Fails when the production SQL removes the same pinned-status, same-timestamp
 *   `Lomo.id < :cursorId` keyset branch.
 * - Fails before the content-flag projection fix because DefaultMainListDao filters content flags
 *   with handwritten LIKE clauses, so analyzer-supported audio and geo/email URL memos are missed.
 *
 * Excludes:
 * - Repository cursor token mapping, daily review randomization, PagingSource invalidation,
 *   migration history, and app UI behavior.
 */
class DefaultMainListDaoSqlRegressionTest : DataFunSpec() {
    private lateinit var databasePath: Path
    private lateinit var driver: DefaultMainListTestJdbcSQLiteDriver
    private lateinit var database: MemoDatabase

    init {
        beforeTest {
            databasePath = Files.createTempFile("default-main-list-dao-", ".db")
            databasePath.deleteIfExists()
            driver = DefaultMainListTestJdbcSQLiteDriver()
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

        test("given mixed pinned and unpinned rows when first candidate page is read then SQL applies stable ordering") {
            `given mixed pinned and unpinned rows when first candidate page is read then SQL applies stable ordering`()
        }

        test("given rowid boundary when newer head row is inserted then first candidate page and count exclude it") {
            `given rowid boundary when newer head row is inserted then first candidate page and count exclude it`()
        }

        test("given cursor from page one when page two is read then SQL advances through same timestamp ids") {
            `given cursor from page one when page two is read then SQL advances through same timestamp ids`()
        }

        test("given analyzer-only content flags when active count is read then SQL filters projected flags") {
            `given analyzer-only content flags when active count is read then SQL filters projected flags`()
        }
    }

    private fun `given mixed pinned and unpinned rows when first candidate page is read then SQL applies stable ordering`() =
        runTest {
            seedMemos(
                memoEntity(id = "unpinned-high", timestamp = 500L),
                memoEntity(id = "pinned-mid-b", timestamp = 400L),
                memoEntity(id = "pinned-mid-a", timestamp = 400L),
                memoEntity(id = "pinned-low", timestamp = 100L),
                memoEntity(id = "unpinned-low", timestamp = 100L),
            )
            pin("pinned-mid-b", "pinned-mid-a", "pinned-low")
            val maxRowId = requireNotNull(database.defaultMainListDao().getDailyReviewCandidateMaxRowId())

            val page =
                database
                    .defaultMainListDao()
                    .getDailyReviewCandidatePage(
                        maxRowId = maxRowId,
                        cursorIsPinned = null,
                        cursorTimestamp = null,
                        cursorId = null,
                        limit = 10,
                    )

            page.map { it.memo.id } shouldBe
                listOf(
                    "pinned-mid-b",
                    "pinned-mid-a",
                    "pinned-low",
                    "unpinned-high",
                    "unpinned-low",
                )
            page.map { it.isPinned } shouldBe listOf(true, true, true, false, false)
        }

    private fun `given rowid boundary when newer head row is inserted then first candidate page and count exclude it`() =
        runTest {
            seedMemos(
                memoEntity(id = "pinned-before-boundary", timestamp = 300L),
                memoEntity(id = "unpinned-before-boundary", timestamp = 200L),
            )
            pin("pinned-before-boundary")
            val maxRowId = requireNotNull(database.defaultMainListDao().getDailyReviewCandidateMaxRowId())

            seedMemos(memoEntity(id = "new-head-after-boundary", timestamp = 900L))
            pin("new-head-after-boundary")

            val page =
                database
                    .defaultMainListDao()
                    .getDailyReviewCandidatePage(
                        maxRowId = maxRowId,
                        cursorIsPinned = null,
                        cursorTimestamp = null,
                        cursorId = null,
                        limit = 10,
                    )
            val count = database.defaultMainListDao().getDailyReviewCandidateCount(maxRowId = maxRowId)

            page.map { it.memo.id } shouldBe listOf("pinned-before-boundary", "unpinned-before-boundary")
            count shouldBe 2
        }

    private fun `given cursor from page one when page two is read then SQL advances through same timestamp ids`() =
        runTest {
            seedMemos(
                memoEntity(id = "pinned-500", timestamp = 500L),
                memoEntity(id = "pinned-400-c", timestamp = 400L),
                memoEntity(id = "pinned-400-b", timestamp = 400L),
                memoEntity(id = "pinned-400-a", timestamp = 400L),
                memoEntity(id = "unpinned-900", timestamp = 900L),
                memoEntity(id = "unpinned-300-b", timestamp = 300L),
                memoEntity(id = "unpinned-300-a", timestamp = 300L),
                memoEntity(id = "unpinned-100", timestamp = 100L),
            )
            pin("pinned-500", "pinned-400-c", "pinned-400-b", "pinned-400-a")
            val maxRowId = requireNotNull(database.defaultMainListDao().getDailyReviewCandidateMaxRowId())

            val firstPage =
                database
                    .defaultMainListDao()
                    .getDailyReviewCandidatePage(
                        maxRowId = maxRowId,
                        cursorIsPinned = null,
                        cursorTimestamp = null,
                        cursorId = null,
                        limit = 2,
                    )
            val cursor = firstPage.last()
            val secondPage =
                database
                    .defaultMainListDao()
                    .getDailyReviewCandidatePage(
                        maxRowId = maxRowId,
                        cursorIsPinned = cursor.isPinned,
                        cursorTimestamp = cursor.memo.timestamp,
                        cursorId = cursor.memo.id,
                        limit = 6,
                    )

            firstPage.map { it.memo.id } shouldBe listOf("pinned-500", "pinned-400-c")
            secondPage.map { it.memo.id } shouldBe
                listOf(
                    "pinned-400-b",
                    "pinned-400-a",
                    "unpinned-900",
                    "unpinned-300-b",
                    "unpinned-300-a",
                    "unpinned-100",
                )
            secondPage.map { it.isPinned } shouldBe listOf(true, true, false, false, false, false)
        }

    private fun `given analyzer-only content flags when active count is read then SQL filters projected flags`() =
        runTest {
            seedMemos(
                memoEntity(id = "todo-tab", timestamp = 100L, content = "  -\t[ ] indented task"),
                memoEntity(id = "audio-attachment", timestamp = 200L, content = "[voice](voice_001.m4a)"),
                memoEntity(id = "geo-url", timestamp = 300L, content = "geo:31.2304,121.4737"),
                memoEntity(id = "mailto-url", timestamp = 400L, content = "mailto:hello@example.com"),
                memoEntity(id = "email-url", timestamp = 500L, content = "hello@example.com"),
                memoEntity(id = "plain", timestamp = 600L, content = "plain memo"),
            )

            val dao = database.defaultMainListDao()

            dao.countFor(hasTodo = true).first() shouldBe 1
            dao.countFor(hasAttachment = true).first() shouldBe 1
            dao.countFor(hasUrl = true).first() shouldBe 3
            dao.countFor(hasTodo = false).first() shouldBe 5
            dao.countFor(hasAttachment = false).first() shouldBe 5
            dao.countFor(hasUrl = false).first() shouldBe 3
        }

    private suspend fun seedMemos(vararg memos: MemoEntity) {
        database.memoWriteDao().insertMemos(memos.toList())
    }

    private suspend fun pin(vararg memoIds: String) {
        memoIds.forEachIndexed { index, memoId ->
            database.memoPinDao().upsertMemoPin(MemoPinEntity(memoId = memoId, pinnedAt = index.toLong()))
        }
    }

    private fun memoEntity(
        id: String,
        timestamp: Long,
        content: String = "content for $id",
    ): MemoEntity {
        val analysis = MemoContentAnalyzer.analyze(content)
        return MemoEntity(
            id = id,
            timestamp = timestamp,
            updatedAt = timestamp,
            content = content,
            searchContent = content,
            rawContent = "- 10:00 $content",
            date = "2026_05_23",
            tags = "",
            imageUrls = "",
            hasTodo = analysis.hasTodo,
            hasAttachment = analysis.hasAttachment,
            hasUrl = analysis.hasUrl,
            geoLocation = null,
        )
    }
}

private fun DefaultMainListDao.countFor(
    hasTodo: Boolean? = null,
    hasAttachment: Boolean? = null,
    hasUrl: Boolean? = null,
) = getCountFlow(
    query = "",
    startDate = null,
    endDate = null,
    sortOption = "CREATED_TIME",
    sortAscending = false,
    hasTodo = hasTodo,
    hasAttachment = hasAttachment,
    hasUrl = hasUrl,
)

private class DefaultMainListTestJdbcSQLiteDriver : SQLiteDriver,
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
