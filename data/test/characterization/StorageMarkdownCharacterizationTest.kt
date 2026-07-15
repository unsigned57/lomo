package com.lomo.data.characterization

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: MarkdownParser storage characterization against fixtures/markdown
 * - Owning layer: data (test-only)
 * - Capability: lock user-visible storage semantics for open Markdown fixtures as golden JSON.
 *
 * Scenarios:
 * - Given each markdown fixture, when characterized, then the result matches the golden under
 *   fixtures/characterization/markdown.
 * - Given LOMO_UPDATE_CHARACTERIZATION=1, when characterized, then goldens may be rewritten
 *   intentionally; without the flag, missing goldens fail closed (no silent bootstrap).
 * - Given invalid UTF-8, when characterized, then outcome is error with class utf8_decode.
 * - Given empty markdown, when characterized, then outcome is ok with zero memos.
 *
 * Observable outcomes:
 * - Stable JSON goldens (ids, content, tags, attachments, spans, byte_length, error_class).
 * - Missing golden without update flag → test failure (contract not auto-invented).
 *
 * TDD proof:
 * - RED when a golden is missing or when parser semantics drift.
 *
 * Excludes:
 * - Compose UI, Room, sync transport, absolute epoch timestamps, production DI.
 *
 * Test Change Justification:
 * - Reason category: Contract correction
 * - Old behavior/assertion being replaced: silent golden invent / weaker fail-closed wording
 * - Why old assertion is no longer correct: missing goldens must fail closed without update flag
 * - Coverage preserved by: still compares full storage characterization goldens per fixture
 * - Why this is not fitting the test to the implementation: locks open Markdown bytes and semantics
 */
class StorageMarkdownCharacterizationTest : DataFunSpec() {
    init {
        test("given fixtures markdown corpus when characterized then goldens match external contract") {
            val markdownRoot = FixtureRepositoryPaths.requireDirectory(FixtureRepositoryPaths.markdownFixtures())
            val goldenRoot = FixtureRepositoryPaths.characterizationMarkdown()
            Files.createDirectories(goldenRoot)

            val fixtures =
                markdownRoot
                    .listDirectoryEntries()
                    .filter { path ->
                        val name = path.name
                        name.endsWith(".md") || name.endsWith(".bin")
                    }.sortedBy { it.name }

            fixtures.isEmpty() shouldBe false

            val update = System.getenv("LOMO_UPDATE_CHARACTERIZATION") == "1"
            for (fixture in fixtures) {
                val actual = StorageMarkdownCharacterization.characterize(fixture)
                val goldenPath = goldenRoot.resolve(fixture.name.substringBeforeLast('.') + ".json")
                if (update) {
                    goldenPath.writeText(StorageMarkdownCharacterization.encode(actual))
                }
                check(Files.exists(goldenPath)) {
                    "missing characterization golden ${goldenPath.fileName}; " +
                        "add a reviewed golden or set LOMO_UPDATE_CHARACTERIZATION=1 after intentional contract change"
                }
                val expected = StorageMarkdownCharacterization.decode(goldenPath.readText())
                actual shouldBe expected
            }
        }
    }
}
