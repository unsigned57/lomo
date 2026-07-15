package com.lomo.data.characterization

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.lomo.data.local.JdbcSQLiteConnection
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.MemoDatabase_Impl
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Behavior Contract:
 * - Unit under test: observable Room query results (language-neutral capability matrix)
 * - Owning layer: data (test-only characterization)
 * - Capability: seed known memos/tags/trash, run list/page/tag/count/trash queries, lock
 *   user-visible result tables (ids, counts, order) without Entity/DAO/Compose types.
 *
 * Scenarios:
 * - Given a seeded workspace with three memos and tags, when list_recent_by_timestamp runs,
 *   then newest ids are returned first.
 * - Given the same seed, when count_all_memos runs, then count is 3.
 * - Given parent/child tags, when tag_page_parent_match pages, then order and offset are stable.
 * - Given trash rows, when trash_list_newest_first runs, then deleted ids are newest-first.
 * - Given tag rows, when tag_counts runs, then per-tag frequencies match the seed.
 *
 * Observable outcomes: JSON under fixtures/characterization/room-query/
 * TDD proof: RED when query result semantics change without golden update.
 * Excludes: Entity/DAO class names, Compose, migration history (separate integration tests).
 */
class RoomQueryBehaviorCharacterizationTest : DataFunSpec() {
    private lateinit var databasePath: Path
    private lateinit var driver: QueryCharJdbcDriver
    private lateinit var database: MemoDatabase

    init {
        beforeTest {
            databasePath = Files.createTempFile("room-query-char-", ".db")
            databasePath.deleteIfExists()
            driver = QueryCharJdbcDriver()
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

        test("given seeded workspace when room query capabilities run then result goldens match") {
            runTest {
                seedWorkspace(database)
                val scenarios =
                    listOf(
                        captureRecentPage(database),
                        captureMemoCount(database),
                        captureTagPage(database),
                        captureTrashList(database),
                        captureTagCounts(database),
                    )
                val goldenRoot =
                    FixtureRepositoryPaths.fixturesRoot().resolve("characterization/room-query")
                Files.createDirectories(goldenRoot)
                val update = System.getenv("LOMO_UPDATE_CHARACTERIZATION") == "1"
                val json =
                    Json {
                        prettyPrint = true
                        prettyPrintIndent = "  "
                        encodeDefaults = true
                    }
                for (scenario in scenarios) {
                    val path = goldenRoot.resolve("${scenario.capability}.v1.json")
                    if (update) {
                        path.writeText(
                            json.encodeToString(RoomQueryScenarioV1.serializer(), scenario) + "\n",
                        )
                    }
                    check(Files.exists(path)) {
                        "missing room-query golden ${path.fileName}; set LOMO_UPDATE_CHARACTERIZATION=1 after review"
                    }
                    val expected =
                        json.decodeFromString(RoomQueryScenarioV1.serializer(), path.readText())
                    scenario shouldBe expected
                }
            }
        }
    }
}

@Serializable
private data class RoomQueryScenarioV1(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    /** Capability name only — no Entity/DAO type names. */
    val capability: String,
    val given: Map<String, String>,
    val then: RoomQueryThenV1,
)

@Serializable
private data class RoomQueryThenV1(
    val ids: List<String> = emptyList(),
    val count: Int? = null,
    val tag_counts: Map<String, Int> = emptyMap(),
)

private suspend fun seedWorkspace(database: MemoDatabase) {
    val newest =
        memo(
            id = "memo-newest",
            timestamp = 300L,
            tags = "project,project/mobile",
            content = "newest body",
        )
    val middle =
        memo(
            id = "memo-middle",
            timestamp = 200L,
            tags = "project/mobile",
            content = "middle body",
        )
    val oldest =
        memo(
            id = "memo-oldest",
            timestamp = 100L,
            tags = "life",
            content = "oldest body",
        )
    database.memoWriteDao().insertMemos(listOf(newest, middle, oldest))
    database.memoTagDao().insertTagRefs(
        listOf(
            MemoTagCrossRefEntity(memoId = newest.id, tag = "project"),
            MemoTagCrossRefEntity(memoId = newest.id, tag = "project/mobile"),
            MemoTagCrossRefEntity(memoId = middle.id, tag = "project/mobile"),
            MemoTagCrossRefEntity(memoId = oldest.id, tag = "life"),
        ),
    )
    database.memoTrashDao().insertTrashMemos(
        listOf(
            TrashMemoEntity(
                id = "trash-1",
                timestamp = 50L,
                updatedAt = 50L,
                content = "trashed",
                rawContent = "- 09:00:00 trashed",
                date = "2024-06-01",
                tags = "",
                imageUrls = "",
            ),
            TrashMemoEntity(
                id = "trash-2",
                timestamp = 90L,
                updatedAt = 90L,
                content = "newer trash",
                rawContent = "- 09:01:00 newer trash",
                date = "2024-06-01",
                tags = "",
                imageUrls = "",
            ),
        ),
    )
}

private suspend fun captureRecentPage(database: MemoDatabase): RoomQueryScenarioV1 {
    val page = database.memoDao().getRecentMemos(limit = 2)
    return RoomQueryScenarioV1(
        capability = "list_recent_by_timestamp",
        given =
            mapOf(
                "seeded_ids" to "memo-newest,memo-middle,memo-oldest",
                "limit" to "2",
            ),
        then = RoomQueryThenV1(ids = page.map { it.id }),
    )
}

private suspend fun captureMemoCount(database: MemoDatabase): RoomQueryScenarioV1 {
    val count = database.memoDao().getMemoCountSync()
    return RoomQueryScenarioV1(
        capability = "count_all_memos",
        given = mapOf("seeded_count" to "3"),
        then = RoomQueryThenV1(count = count),
    )
}

private suspend fun captureTagPage(database: MemoDatabase): RoomQueryScenarioV1 {
    val first =
        database.memoSearchDao().getMemosByTagPage(
            tag = "project",
            tagPrefix = "project/%",
            limit = 2,
            offset = 0,
        )
    val offset =
        database.memoSearchDao().getMemosByTagPage(
            tag = "project",
            tagPrefix = "project/%",
            limit = 2,
            offset = 1,
        )
    return RoomQueryScenarioV1(
        capability = "tag_page_parent_match",
        given =
            mapOf(
                "tag" to "project",
                "limit" to "2",
                "offsets" to "0,1",
            ),
        then =
            RoomQueryThenV1(
                ids = first.map { it.id } + listOf("|") + offset.map { it.id },
            ),
    )
}

private suspend fun captureTrashList(database: MemoDatabase): RoomQueryScenarioV1 {
    val trash = database.memoTrashDao().getDeletedMemos()
    return RoomQueryScenarioV1(
        capability = "trash_list_newest_first",
        given = mapOf("seeded_trash" to "trash-1,trash-2"),
        then = RoomQueryThenV1(ids = trash.map { it.id }),
    )
}

private suspend fun captureTagCounts(database: MemoDatabase): RoomQueryScenarioV1 {
    val rows = database.memoStatisticsDao().getTagCounts()
    val map = rows.associate { it.name to it.count }
    return RoomQueryScenarioV1(
        capability = "tag_counts",
        given = mapOf("seeded_tags" to "project,project/mobile,life"),
        then = RoomQueryThenV1(tag_counts = map.toSortedMap()),
    )
}

private fun memo(
    id: String,
    timestamp: Long,
    tags: String,
    content: String,
): MemoEntity =
    MemoEntity(
        id = id,
        timestamp = timestamp,
        updatedAt = timestamp,
        content = content,
        searchContent = content,
        rawContent = "- 10:00:00 $content",
        date = "2024-06-01",
        tags = tags,
        imageUrls = "",
        geoLocation = null,
    )

private class QueryCharJdbcDriver :
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
