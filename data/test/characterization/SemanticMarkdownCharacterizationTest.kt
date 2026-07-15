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
 * - Unit under test: UI-neutral semantic counters via MemoTextProcessor + regex
 * - Owning layer: data (test-only)
 * - Capability: lock tag/attachment/checkbox/link counters without Compose IR.
 *
 * Scenarios:
 * - Given each utf-8 markdown fixture, when characterized, then goldens match.
 * - Missing golden without LOMO_UPDATE_CHARACTERIZATION=1 fails closed.
 *
 * Observable outcomes: stable JSON goldens under fixtures/characterization/semantic.
 * TDD proof: RED when counters drift or golden missing.
 * Excludes: Compose, Room, formal RenderDocument, ui-components parseMarkdownSemanticDocument.
 * Non-claim: this is **not** the production UI semantic parser contract.
 */
class SemanticMarkdownCharacterizationTest : DataFunSpec() {
    init {
        test("given fixtures markdown when semantic characterized then goldens match") {
            val markdownRoot =
                FixtureRepositoryPaths.requireDirectory(FixtureRepositoryPaths.markdownFixtures())
            val goldenRoot = FixtureRepositoryPaths.fixturesRoot().resolve("characterization/semantic")
            Files.createDirectories(goldenRoot)
            val fixtures =
                markdownRoot
                    .listDirectoryEntries()
                    .filter { it.name.endsWith(".md") }
                    .sortedBy { it.name }
            fixtures.isEmpty() shouldBe false
            val update = System.getenv("LOMO_UPDATE_CHARACTERIZATION") == "1"
            for (fixture in fixtures) {
                val actual = SemanticMarkdownCharacterization.characterize(fixture)
                val goldenPath = goldenRoot.resolve(fixture.name.substringBeforeLast('.') + ".json")
                if (update) {
                    goldenPath.writeText(SemanticMarkdownCharacterization.encode(actual))
                }
                check(Files.exists(goldenPath)) {
                    "missing semantic golden ${goldenPath.fileName}; set LOMO_UPDATE_CHARACTERIZATION=1 after review"
                }
                val expected = SemanticMarkdownCharacterization.decode(goldenPath.readText())
                actual shouldBe expected
            }
        }
    }
}
