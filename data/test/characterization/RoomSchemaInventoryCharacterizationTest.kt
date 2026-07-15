package com.lomo.data.characterization

import com.lomo.data.local.MEMO_DATABASE_VERSION
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Behavior Contract:
 * - Unit under test: Room schema surface — entities, DAOs, database version, exported schema,
 *   and DAO @Query method inventory under data/src/local
 * - Owning layer: data (test-only characterization)
 * - Capability: lock the external Room schema/query surface for stage-0 exit.
 *
 * Scenarios:
 * - Given entity/DAO sources and MemoDatabase version, when inventoried, then goldens match and
 *   the Room schema export for the current version exists.
 *
 * Observable outcomes: goldens under fixtures/characterization/room.
 * TDD proof: RED when entity/DAO/query/version drifts without golden update.
 * Excludes: Compose UI. Runtime insert/query behavior is covered by
 *   RoomRuntimeQueryCharacterizationTest + migration integration tests.
 */
class RoomSchemaInventoryCharacterizationTest : DataFunSpec() {
    init {
        test("given room schema sources when inventoried then goldens and export match") {
            val root = FixtureRepositoryPaths.repositoryRoot()
            val entityDir = root.resolve("data/src/local/entity")
            val daoDir = root.resolve("data/src/local/dao")
            check(Files.isDirectory(entityDir)) { "missing $entityDir" }
            check(Files.isDirectory(daoDir)) { "missing $daoDir" }

            val entities =
                entityDir
                    .listDirectoryEntries("*.kt")
                    .map { it.name.removeSuffix(".kt") }
                    .filter { it.endsWith("Entity") }
                    .sorted()
            entities.isEmpty() shouldBe false
            entities shouldContain "MemoEntity"

            val daos =
                daoDir
                    .listDirectoryEntries("*.kt")
                    .map { it.name.removeSuffix(".kt") }
                    .filter { it.endsWith("Dao") }
                    .sorted()
            daos.isEmpty() shouldBe false
            daos shouldContain "MemoDao"

            val queryMethods =
                daoDir
                    .listDirectoryEntries("*.kt")
                    .flatMap { path ->
                        val text = path.readText()
                        QUERY_METHOD_REGEX
                            .findAll(text)
                            .map { match ->
                                val method = match.groupValues[1]
                                "${path.name.removeSuffix(".kt")}.$method"
                            }.toList()
                    }.sorted()
            queryMethods.isEmpty() shouldBe false

            val schemaExport =
                root
                    .resolve("data/schemas/com.lomo.data.local.MemoDatabase")
                    .resolve("$MEMO_DATABASE_VERSION.json")
            check(Files.isRegularFile(schemaExport)) {
                "missing Room schema export for version $MEMO_DATABASE_VERSION at $schemaExport"
            }

            val goldenRoot = FixtureRepositoryPaths.fixturesRoot().resolve("characterization/room")
            Files.createDirectories(goldenRoot)
            val goldenPath = goldenRoot.resolve("schema-surface.v1.json")
            val actual =
                RoomSchemaSurfaceV1(
                    schemaVersion = 1,
                    databaseVersion = MEMO_DATABASE_VERSION,
                    entities = entities,
                    daos = daos,
                    queryMethods = queryMethods,
                    schemaExportRelativePath =
                        "data/schemas/com.lomo.data.local.MemoDatabase/$MEMO_DATABASE_VERSION.json",
                )
            val update = System.getenv("LOMO_UPDATE_CHARACTERIZATION") == "1"
            val json =
                Json {
                    prettyPrint = true
                    prettyPrintIndent = "  "
                    encodeDefaults = true
                }
            if (update) {
                goldenPath.writeText(
                    json.encodeToString(RoomSchemaSurfaceV1.serializer(), actual) + "\n",
                )
            }
            check(Files.exists(goldenPath)) {
                "missing room schema-surface golden; set LOMO_UPDATE_CHARACTERIZATION=1 after review"
            }
            val expected =
                json.decodeFromString(RoomSchemaSurfaceV1.serializer(), goldenPath.readText())
            actual shouldBe expected
        }
    }
}

private val QUERY_METHOD_REGEX =
    Regex(
        """@Query\s*\([\s\S]*?\)\s*(?:suspend\s+)?(?:fun|override\s+fun)\s+(\w+)\s*\(""",
    )

@Serializable
private data class RoomSchemaSurfaceV1(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("database_version") val databaseVersion: Int,
    val entities: List<String>,
    val daos: List<String>,
    @SerialName("query_methods") val queryMethods: List<String>,
    @SerialName("schema_export_relative_path") val schemaExportRelativePath: String,
)
