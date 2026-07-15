package com.lomo.data.characterization

import com.lomo.data.parser.MarkdownParser
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.matchers.shouldBe
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/*
 * Behavior Contract:
 * - Unit under test: unedited storage parse **double-parse stability** for open Markdown fixtures
 * - Owning layer: data (test-only)
 * - Capability: second parse of the same utf-8 fixture bytes yields the same memo ids/content/tags
 *   and source spans.
 *
 * Scenarios:
 * - Given a successful utf-8 markdown fixture, when parsed twice, then ids, content, tags, and
 *   source spans match.
 *
 * Observable outcomes: id/content/tag/span parity across parses.
 * TDD proof: RED when parse is non-deterministic for fixed stem.
 * Excludes: destructive edits, UI. Byte-stable write-back is
 *   UneditedMemoWriteBackCharacterizationTest.
 */
class StorageWriteBackCharacterizationTest : DataFunSpec() {
    init {
        test("given utf-8 markdown fixtures when parsed twice then memo identities and spans stay stable") {
            val parser = MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
            val markdownRoot =
                FixtureRepositoryPaths.requireDirectory(FixtureRepositoryPaths.markdownFixtures())
            val fixtures =
                markdownRoot
                    .listDirectoryEntries()
                    .filter { it.name.endsWith(".md") }
                    .sortedBy { it.name }
            fixtures.isEmpty() shouldBe false
            for (fixture in fixtures) {
                val stem =
                    StorageMarkdownCharacterization.filenameStemByFixture[fixture.name]
                        ?: continue
                val text = fixture.readText(Charsets.UTF_8)
                val first = parser.parseDocument(text, stem, fallbackTimestampMillis = 0L).blocks
                val second = parser.parseDocument(text, stem, fallbackTimestampMillis = 0L).blocks
                second.size shouldBe first.size
                for (index in first.indices) {
                    second[index].memo.id shouldBe first[index].memo.id
                    second[index].memo.content shouldBe first[index].memo.content
                    second[index].memo.tags shouldBe first[index].memo.tags
                    second[index].span shouldBe first[index].span
                }
            }
        }
    }
}
