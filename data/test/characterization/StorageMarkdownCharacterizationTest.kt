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
 *   fixtures/characterization/markdown (or is rewritten when LOMO_UPDATE_CHARACTERIZATION=1).
 * - Given invalid UTF-8, when characterized, then outcome is error with class utf8_decode.
 * - Given empty markdown, when characterized, then outcome is ok with zero memos.
 *
 * Observable outcomes:
 * - Stable JSON goldens (ids, content, tags, attachments, spans, byte_length, error_class).
 *
 * TDD proof:
 * - RED before goldens exist or when parser semantics drift.
 *
 * Excludes:
 * - Compose UI, Room, sync transport, absolute epoch timestamps, production DI.
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
                if (update || !Files.exists(goldenPath)) {
                    goldenPath.writeText(StorageMarkdownCharacterization.encode(actual))
                }
                val expected = StorageMarkdownCharacterization.decode(goldenPath.readText())
                actual shouldBe expected
            }
        }
    }
}
