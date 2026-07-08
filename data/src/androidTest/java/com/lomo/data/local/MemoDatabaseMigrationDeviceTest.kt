/*
 * Test Contract:
 * - Unit under test: MemoDatabase Room open path.
 * - Behavior focus: a stable 1.3.0 DB v46 file must open through the current Room3 builder
 *   with bundled SQLite and all production migrations.
 * - Observable outcomes: Room open completes, the memo row is preserved, and the database
 *   reaches the current user_version.
 * - Red phase: Fails before the fix when the production Room open path cannot upgrade a v46
 *   database file in-place.
 * - Excludes: Koin graph construction, app navigation/startup UI, and repository sync behavior.
 */
package com.lomo.data.local

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoDatabaseMigrationDeviceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "memo-v46-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun opensStableV46DatabaseWithProductionRoomBuilder() {
        createVersion46Database()

        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = MEMO_DATABASE_VERSION,
            databaseName = databaseName,
            migrationEdges = ALL_DATABASE_MIGRATION_EDGES,
        )

        val database =
            Room
                .databaseBuilder(context, MemoDatabase::class.java, databaseName)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_DATABASE_MIGRATIONS)
                .addCallback(DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback())
                .build()

        try {
            runBlocking {
                database.useReaderConnection { connection ->
                    connection.usePrepared("SELECT COUNT(*) FROM `$MEMO_TABLE`") { statement ->
                        check(statement.step()) { "Memo count query returned no rows" }
                        assertEquals(1L, statement.getLong(0))
                    }
                    connection.usePrepared("PRAGMA user_version") { statement ->
                        check(statement.step()) { "PRAGMA user_version returned no rows" }
                        assertEquals(MEMO_DATABASE_VERSION.toLong(), statement.getLong(0))
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun createVersion46Database() {
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        BundledSQLiteDriver().open(databaseFile.path).use { connection ->
            schemaSnapshotStatements(version = 46).forEach(connection::execSQL)
            connection.execSQL("PRAGMA user_version = 46")
            seedMemo(connection)
        }
    }

    private fun seedMemo(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO `$MEMO_TABLE` (`id`, `timestamp`, `updatedAt`, `content`, `rawContent`, `date`, `tags`, `imageUrls`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "memo-1",
                1_001L,
                1_002L,
                "stable v46 memo",
                "- 10:00 stable v46 memo",
                "2026_05_01",
                "alpha",
                "cover.png",
            ),
        )
    }

    private fun schemaSnapshotStatements(version: Int): List<String> {
        val schemaJson =
            InstrumentationRegistry
                .getInstrumentation()
                .context
                .assets
                .open("com.lomo.data.local.MemoDatabase/$version.json")
                .bufferedReader()
                .use { reader -> reader.readText() }
        val database = Json.parseToJsonElement(schemaJson).jsonObject.getValue("database").jsonObject
        val entities = database.getValue("entities").jsonArray
        return buildList {
            entities.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                add(replaceTablePlaceholder(entity.getValue("createSql").jsonPrimitive.content, tableName))
                val indices = entity["indices"]?.jsonArray ?: JsonArray(emptyList())
                indices.forEach { index ->
                    add(replaceTablePlaceholder(index.jsonObject.getValue("createSql").jsonPrimitive.content, tableName))
                }
            }
        }
    }

    private fun replaceTablePlaceholder(
        sql: String,
        tableName: String,
    ): String = sql.replace("`${'$'}{TABLE_NAME}`", "`$tableName`")
}
