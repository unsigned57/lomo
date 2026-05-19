/*
 * Behavior Contract:
 * - Unit under test: DatabaseMigrationSupport / DatabaseMigrations
 * - Behavior focus: end-to-end database migration with real file I/O and SQLite transactions.
 * - Observable outcomes: successful schema upgrade, data preservation, migration failure rollback.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: specific DAO logic, UI rendering, cloud sync details.
 */
package com.lomo.data.local

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import androidx.room3.migration.Migration
import com.lomo.data.util.SearchTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

class DatabaseMigrationRealIntegrationTest : DataFunSpec() {
    init {
        test("direct and incremental migration from v44 converge on identical final schema and representative data") { `direct and incremental migration from v44 converge on identical final schema and representative data`() }

        test("stable baseline direct migration preserves current sqlite column contract on real database") { `stable baseline direct migration preserves current sqlite column contract on real database`() }

        test("stable baseline direct migration leaves external content fts searchable and trigger managed") { `stable baseline direct migration leaves external content fts searchable and trigger managed`() }
    }


    private fun `direct and incremental migration from v44 converge on identical final schema and representative data`() {
        createVersion44Database().use { incremental ->
            createVersion44Database().use { direct ->
                migrateIncrementally(incremental)
                migrateDirectlyFrom44(direct)

                schemaObjects(direct) shouldBe schemaObjects(incremental)
                (schemaObjects(direct).containsAll(
                        listOf(
                            SchemaObject(type = "table", name = "lomo_fts", tableName = "lomo_fts"),
                            SchemaObject(type = "table", name = "lomo_fts_maintenance", tableName = "lomo_fts_maintenance"),
                            SchemaObject(type = "trigger", name = "lomo_fts_ai", tableName = MEMO_TABLE),
                            SchemaObject(type = "trigger", name = "lomo_fts_ad", tableName = MEMO_TABLE),
                            SchemaObject(type = "trigger", name = "lomo_fts_au", tableName = MEMO_TABLE),
                        ),
                    )).shouldBeTrue()
                tableRows(direct, MEMO_TABLE) shouldBe tableRows(incremental, MEMO_TABLE)
                tableRows(direct, TRASH_MEMO_TABLE) shouldBe tableRows(incremental, TRASH_MEMO_TABLE)
                tableRows(direct, MEMO_TAG_CROSS_REF_TABLE) shouldBe tableRows(incremental, MEMO_TAG_CROSS_REF_TABLE)
                tableRows(direct, MEMO_IMAGE_ATTACHMENT_TABLE) shouldBe tableRows(incremental, MEMO_IMAGE_ATTACHMENT_TABLE)
                tableRows(direct, MEMO_FILE_OUTBOX_TABLE) shouldBe tableRows(incremental, MEMO_FILE_OUTBOX_TABLE)
                tableRows(direct, WEBDAV_SYNC_METADATA_TABLE) shouldBe tableRows(incremental, WEBDAV_SYNC_METADATA_TABLE)
                tableRows(direct, S3_SYNC_METADATA_TABLE) shouldBe tableRows(incremental, S3_SYNC_METADATA_TABLE)
            }
        }
    }

    private fun `stable baseline direct migration preserves current sqlite column contract on real database`() {
        createVersion44Database().use { connection ->
            migrateDirectlyFrom44(connection)

            assertColumn(
                connection = connection,
                table = MEMO_TABLE,
                column = "searchContent",
                type = "TEXT",
                notNull = true,
            )
            assertColumn(
                connection = connection,
                table = MEMO_FILE_OUTBOX_TABLE,
                column = "operation",
                type = "INTEGER",
                notNull = true,
            )
            assertColumn(
                connection = connection,
                table = WEBDAV_SYNC_METADATA_TABLE,
                column = "local_fingerprint",
                type = "TEXT",
                notNull = false,
            )
            assertColumn(
                connection = connection,
                table = S3_SYNC_METADATA_TABLE,
                column = "local_size",
                type = "INTEGER",
                notNull = false,
            )
            assertColumn(
                connection = connection,
                table = S3_SYNC_METADATA_TABLE,
                column = "local_fingerprint",
                type = "TEXT",
                notNull = false,
            )
            (foreignKeys(connection, MEMO_TAG_CROSS_REF_TABLE).any { foreignKey ->
                    foreignKey["from"] == "memoId" &&
                        foreignKey["to"] == "id" &&
                        foreignKey["table"] == MEMO_TABLE &&
                        foreignKey["onDelete"] == "CASCADE"
                }).shouldBeTrue()
        }
    }

    private fun `stable baseline direct migration leaves external content fts searchable and trigger managed`() {
        createVersion44Database().use { connection ->
            migrateDirectlyFrom44(connection)

            querySingleString(connection, "SELECT `searchContent` FROM `$MEMO_TABLE` WHERE `id` = 'memo-1'") shouldBe SearchTokenizer.tokenize("苏格拉底 legacy memo")
            ftsSearch(connection, "legacy*") shouldBe listOf("memo-1")
            ftsSearch(connection, "苏格*") shouldBe listOf("memo-1")

            connection.prepareStatement(
                """
                INSERT INTO `$MEMO_TABLE`(
                    `id`, `timestamp`, `updatedAt`, `content`, `searchContent`, `rawContent`, `date`, `tags`, `imageUrls`, `geoLocation`
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "memo-new")
                statement.setLong(2, 2_001L)
                statement.setLong(3, 2_002L)
                statement.setString(4, "人工智能 update")
                statement.setString(5, SearchTokenizer.tokenize("人工智能 update"))
                statement.setString(6, "- 11:00 人工智能 update")
                statement.setString(7, "2026_05_02")
                statement.setString(8, "gamma")
                statement.setString(9, "new.png")
                statement.setString(10, null)
                statement.executeUpdate()
            }
            (ftsSearch(connection, "人工*").contains("memo-new")).shouldBeTrue()

            connection.prepareStatement(
                """
                UPDATE `$MEMO_TABLE`
                SET `content` = ?, `searchContent` = ?, `rawContent` = ?, `updatedAt` = ?
                WHERE `id` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "Android migration note")
                statement.setString(2, SearchTokenizer.tokenize("Android migration note"))
                statement.setString(3, "- 12:00 Android migration note")
                statement.setLong(4, 2_010L)
                statement.setString(5, "memo-1")
                statement.executeUpdate()
            }
            (ftsSearch(connection, "Andr*").contains("memo-1")).shouldBeTrue()
            (ftsSearch(connection, "legacy*").isEmpty()).shouldBeTrue()

            connection.prepareStatement("DELETE FROM `$MEMO_TABLE` WHERE `id` = ?").use { statement ->
                statement.setString(1, "memo-new")
                statement.executeUpdate()
            }
            (ftsSearch(connection, "人工*").isEmpty()).shouldBeTrue()
        }
    }

    private fun createVersion44Database(): Connection {
        val databaseFile = Files.createTempFile("memo-v44-", ".db")
        val connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            schemaSnapshotStatements(44).forEach(statement::execute)
            statement.execute("PRAGMA user_version = 44")
        }
        seedVersion44Data(connection)
        return connection
    }

    private fun seedVersion44Data(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO `$MEMO_TABLE` (`id`, `timestamp`, `updatedAt`, `content`, `rawContent`, `date`, `tags`, `imageUrls`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "memo-1")
            statement.setLong(2, 1_001L)
            statement.setLong(3, 1_002L)
            statement.setString(4, "苏格拉底 legacy memo")
            statement.setString(5, "- 10:00 苏格拉底 legacy memo")
            statement.setString(6, "2026_05_01")
            statement.setString(7, "alpha,beta")
            statement.setString(8, "cover.png,inline.png")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO `$TRASH_MEMO_TABLE` (`id`, `timestamp`, `updatedAt`, `content`, `rawContent`, `date`, `tags`, `imageUrls`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "trash-1")
            statement.setLong(2, 900L)
            statement.setLong(3, 901L)
            statement.setString(4, "trashed memo")
            statement.setString(5, "- 09:00 trashed memo")
            statement.setString(6, "2026_04_30")
            statement.setString(7, "")
            statement.setString(8, "trash.png")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO `$MEMO_TAG_CROSS_REF_TABLE` (`memoId`, `tag`)
            VALUES (?, ?), (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "memo-1")
            statement.setString(2, "alpha")
            statement.setString(3, "memo-1")
            statement.setString(4, "beta")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO `$LOCAL_FILE_STATE_TABLE`(
                `filename`, `isTrash`, `saf_uri`, `last_known_modified_time`, `missing_since`, `missing_count`, `last_seen_at`
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "memo-1.md")
            statement.setInt(2, 0)
            statement.setString(3, "content://memo-1")
            statement.setLong(4, 1_010L)
            statement.setObject(5, null)
            statement.setInt(6, 0)
            statement.setLong(7, 1_011L)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO `$MEMO_FILE_OUTBOX_TABLE`(
                `id`, `operation`, `memoId`, `memoDate`, `memoTimestamp`, `memoRawContent`,
                `newContent`, `createRawContent`, `createdAt`, `updatedAt`, `retryCount`,
                `lastError`, `claimToken`, `claimUpdatedAt`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, 1L)
            statement.setString(2, "UPDATE")
            statement.setString(3, "memo-1")
            statement.setString(4, "2026_05_01")
            statement.setLong(5, 1_001L)
            statement.setString(6, "- 10:00 苏格拉底 legacy memo")
            statement.setString(7, "patched content")
            statement.setObject(8, null)
            statement.setLong(9, 1_020L)
            statement.setLong(10, 1_021L)
            statement.setInt(11, 2)
            statement.setString(12, "network")
            statement.setString(13, "claim-1")
            statement.setLong(14, 1_022L)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO `$WEBDAV_SYNC_METADATA_TABLE`(
                `relative_path`, `remote_path`, `etag`, `remote_last_modified`,
                `local_last_modified`, `last_synced_at`, `last_resolved_direction`, `last_resolved_reason`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "memo-1.md")
            statement.setString(2, "/remote/memo-1.md")
            statement.setString(3, "etag-1")
            statement.setLong(4, 1_030L)
            statement.setLong(5, 1_031L)
            statement.setLong(6, 1_032L)
            statement.setString(7, "UPLOAD")
            statement.setString(8, "LOCAL_NEWER")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO `$S3_SYNC_METADATA_TABLE`(
                `relative_path`, `remote_path`, `etag`, `remote_last_modified`,
                `local_last_modified`, `last_synced_at`, `last_resolved_direction`, `last_resolved_reason`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "memo-1.md")
            statement.setString(2, "bucket/memo-1.md")
            statement.setString(3, "s3-etag-1")
            statement.setLong(4, 1_040L)
            statement.setLong(5, 1_041L)
            statement.setLong(6, 1_042L)
            statement.setString(7, "DOWNLOAD")
            statement.setString(8, "REMOTE_NEWER")
            statement.executeUpdate()
        }
    }

    private fun migrateIncrementally(connection: Connection) {
        incrementalPathFrom44().forEach { migration ->
            migration.migrateForTest(JdbcSQLiteConnection(connection))
        }
    }

    private fun migrateDirectlyFrom44(connection: Connection) {
        STABLE_BASELINE_DIRECT_MIGRATIONS
            .first { it.startVersion == 44 && it.endVersion == MEMO_DATABASE_VERSION }
            .migrateForTest(JdbcSQLiteConnection(connection))
    }

    private fun incrementalPathFrom44(): List<Migration> =
        ALL_DATABASE_MIGRATIONS
            .filter { it.startVersion in 44 until MEMO_DATABASE_VERSION && it.endVersion == it.startVersion + 1 }
            .sortedBy(Migration::startVersion)

    private fun schemaObjects(connection: Connection): List<SchemaObject> =
        connection.createStatement().executeQuery(
            """
            SELECT `type`, `name`, `tbl_name`
            FROM `sqlite_master`
            WHERE `name` NOT LIKE 'sqlite_%'
              AND `name` NOT IN ('room_master_table', 'lomo_fts_config', 'lomo_fts_content', 'lomo_fts_data', 'lomo_fts_docsize', 'lomo_fts_idx')
            ORDER BY `type`, `name`
            """.trimIndent(),
        ).use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        SchemaObject(
                            type = resultSet.getString("type"),
                            name = resultSet.getString("name"),
                            tableName = resultSet.getString("tbl_name"),
                        ),
                    )
                }
            }
        }

    private fun tableRows(
        connection: Connection,
        table: String,
    ): List<Map<String, String?>> {
        val columns = tableColumns(connection, table)
        val selectColumns = columns.joinToString(", ") { column -> quoteIdentifier(column.name) }
        val orderByClause =
            columns.filter { it.primaryKeyPosition > 0 }
                .sortedBy(TableColumn::primaryKeyPosition)
                .joinToString(", ") { column -> quoteIdentifier(column.name) }
                .ifEmpty { "rowid" }
        return connection.createStatement().executeQuery(
            "SELECT $selectColumns FROM ${quoteIdentifier(table)} ORDER BY $orderByClause",
        ).use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    val row = linkedMapOf<String, String?>()
                    columns.forEachIndexed { index, column ->
                        row[column.name] = resultSet.getObject(index + 1)?.toString()
                    }
                    add(row)
                }
            }
        }
    }

    private fun tableColumns(
        connection: Connection,
        table: String,
    ): List<TableColumn> =
        connection.createStatement().executeQuery("PRAGMA table_info(${quoteIdentifier(table)})").use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        TableColumn(
                            name = resultSet.getString("name"),
                            type = resultSet.getString("type"),
                            notNull = resultSet.getInt("notnull") != 0,
                            primaryKeyPosition = resultSet.getInt("pk"),
                        ),
                    )
                }
            }
        }

    private fun foreignKeys(
        connection: Connection,
        table: String,
    ): List<Map<String, String>> =
        connection.createStatement().executeQuery("PRAGMA foreign_key_list(${quoteIdentifier(table)})").use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        mapOf(
                            "table" to resultSet.getString("table"),
                            "from" to resultSet.getString("from"),
                            "to" to resultSet.getString("to"),
                            "onDelete" to resultSet.getString("on_delete"),
                        ),
                    )
                }
            }
        }

    private fun assertColumn(
        connection: Connection,
        table: String,
        column: String,
        type: String,
        notNull: Boolean,
    ) {
        val actual =
            tableColumns(connection, table)
                .firstOrNull { it.name == column }
                ?: error("Missing column $table.$column")
        actual.type shouldBe type
        actual.notNull shouldBe notNull
    }

    private fun ftsSearch(
        connection: Connection,
        matchQuery: String,
    ): List<String> =
        connection.prepareStatement(
            """
            SELECT `$MEMO_TABLE`.`id`
            FROM `$MEMO_TABLE`
            INNER JOIN `lomo_fts` ON `lomo_fts`.`rowid` = `$MEMO_TABLE`.`rowid`
            WHERE `lomo_fts` MATCH ?
            ORDER BY `$MEMO_TABLE`.`timestamp` DESC
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, matchQuery)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getString(1))
                    }
                }
            }
        }

    private fun querySingleString(
        connection: Connection,
        sql: String,
    ): String? =
        connection.createStatement().executeQuery(sql).use { resultSet ->
            if (resultSet.next()) resultSet.getString(1) else null
        }

    private fun schemaSnapshotStatements(version: Int): List<String> {
        val schemaJson = Files.readString(SCHEMA_DIRECTORY.resolve("$version.json"))
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
    ): String = sql.replace("`${'$'}{TABLE_NAME}`", quoteIdentifier(tableName))

    private fun quoteIdentifier(value: String): String = "`$value`"

    private data class SchemaObject(
        val type: String,
        val name: String,
        val tableName: String,
    )

    private data class TableColumn(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
    )

    private companion object {
        val SCHEMA_DIRECTORY: Path by lazy {
            val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            val relativeSchemaPath = Path.of("data", "schemas", "com.lomo.data.local.MemoDatabase")
            listOfNotNull(
                workingDirectory.resolve(relativeSchemaPath),
                workingDirectory.resolve("schemas").resolve("com.lomo.data.local.MemoDatabase"),
                workingDirectory.parent?.resolve(relativeSchemaPath),
            ).firstOrNull(Files::exists)
                ?: error("Unable to locate exported Room schemas from $workingDirectory")
        }
    }
}
