package com.lomo.ui.characterization

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: parseMarkdownSemanticDocument (UI semantic parser)
 * - Owning layer: ui-components (test-only characterization)
 * - Capability: lock semantic block/kind/task/link/image fingerprints for fixtures/markdown.
 *
 * Scenarios:
 * - Given each utf-8 markdown fixture, when parsed by the real UI semantic parser, then goldens match.
 * - Missing golden without LOMO_UPDATE_CHARACTERIZATION=1 fails closed.
 *
 * Observable outcomes: JSON goldens under fixtures/characterization/semantic-ui.
 * TDD proof: RED when UI parser output drifts or golden missing.
 * Excludes: Compose layout, pixel rendering, storage MemoParser ids.
 */
class UiSemanticMarkdownCharacterizationTest : UiComponentsFunSpec() {
    init {
        test("given fixtures markdown when UI semantic characterized then goldens match") {
            val markdownRoot = requireDirectory(fixturesRoot().resolve("markdown"))
            val goldenRoot = fixturesRoot().resolve("characterization/semantic-ui")
            Files.createDirectories(goldenRoot)
            val fixtures =
                markdownRoot
                    .listDirectoryEntries()
                    .filter { it.name.endsWith(".md") }
                    .sortedBy { it.name }
            fixtures.isEmpty() shouldBe false
            val update = System.getenv("LOMO_UPDATE_CHARACTERIZATION") == "1"
            for (fixture in fixtures) {
                val actual = UiSemanticMarkdownCharacterization.characterize(fixture)
                val goldenPath = goldenRoot.resolve(fixture.name.substringBeforeLast('.') + ".json")
                if (update) {
                    goldenPath.writeText(UiSemanticMarkdownCharacterization.encode(actual))
                }
                check(Files.exists(goldenPath)) {
                    "missing UI semantic golden ${goldenPath.fileName}; set LOMO_UPDATE_CHARACTERIZATION=1 after review"
                }
                val expected =
                    UiSemanticMarkdownCharacterization.decode(goldenPath.readText())
                actual shouldBe expected
            }
        }
    }
}

private fun fixturesRoot(): Path {
    var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    repeat(8) {
        val fixtures = current.resolve("fixtures")
        val rust = current.resolve("rust")
        if (Files.isDirectory(fixtures) && Files.isDirectory(rust)) {
            return fixtures
        }
        current = current.parent ?: error("repository root not found")
    }
    error("fixtures root not found from user.dir=${System.getProperty("user.dir")}")
}

private fun requireDirectory(path: Path): Path {
    check(Files.isDirectory(path)) { "expected directory at $path" }
    return path
}
