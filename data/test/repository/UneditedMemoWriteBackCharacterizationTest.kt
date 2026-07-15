package com.lomo.data.repository

import com.lomo.data.characterization.FixtureRepositoryPaths
import com.lomo.data.characterization.StorageMarkdownCharacterization
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.matchers.shouldBe
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/*
 * Behavior Contract:
 * - Unit under test: unedited memo write-back via rebuildMemoDocument + source spans
 * - Owning layer: data (test-only characterization)
 * - Capability: identity replace of every memo span yields the same open-file bytes as the
 *   original fixture, including UTF-8 BOM and CRLF/LF separators (not LF-normalized text).
 *
 * Scenarios:
 * - Given a successful utf-8 markdown fixture (incl. bom-newline CRLF), when each memo span is
 *   rewritten with the same original lines, then full document bytes are unchanged.
 * - Given the same fixture, when identity rebuild runs, then content text is unchanged before
 *   re-encoding.
 *
 * Observable outcomes: originalBytes == rewrittenBytes.
 * TDD proof: RED when rewrite drops CR, BOM, or trailing separators.
 * Excludes: destructive edits, UI semantic IR, Room.
 *
 * Test Change Justification:
 * - Reason category: Contract correction
 * - Old behavior/assertion being replaced: LF-normalized text equality after CRLF strip
 * - Why old assertion is no longer correct: plan requires unedited open-file byte stability
 * - Coverage preserved by: still exercises identity span rewrite for every markdown fixture
 * - Why this is not fitting the test to the implementation: asserts production invariant that
 *   unedited write-back must not change on-disk bytes (BOM/CRLF), matching plan P0-07
 */
class UneditedMemoWriteBackCharacterizationTest : DataFunSpec() {
    init {
        test("given utf-8 fixtures when unedited span rewrite runs then open-file bytes stay stable") {
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
                val originalBytes = fixture.readBytes()
                val (bom, content) = decodeUtf8WithOptionalBom(originalBytes)
                val document =
                    parser.parseDocument(content, stem, fallbackTimestampMillis = 0L)
                // Use the same line model as MarkdownParser (content.lines()) for span indices.
                // Rejoin with the original open-file separator so CRLF/LF/BOM bytes are preserved.
                // Identity contract: each span rewrite with the existing lines must leave text equal.
                var workingContent = content
                for (block in document.blocks.sortedByDescending { it.span.startLine }) {
                    val parseLines = workingContent.lines()
                    if (parseLines.isEmpty()) {
                        continue
                    }
                    val start = block.span.startLine.coerceIn(0, parseLines.lastIndex)
                    val end =
                        block.span.endLine.coerceIn(start, parseLines.lastIndex)
                    val replacement = parseLines.subList(start, end + 1).toList()
                    val next =
                        rebuildMemoDocument(
                            originalContent = workingContent,
                            startIndex = start,
                            endIndex = end,
                            replacementLines = replacement,
                        )
                    check(next == workingContent) {
                        "${fixture.name}: identity rebuild changed text at span $start..$end " +
                            "(spanEnd=${block.span.endLine} lines=${parseLines.size} " +
                            "len ${workingContent.length}->${next.length})"
                    }
                    workingContent = next
                }
                // Open-file byte stability including BOM + CRLF (not LF-normalized text).
                val rewrittenBytes = bom + workingContent.toByteArray(Charsets.UTF_8)
                check(rewrittenBytes.contentEquals(originalBytes)) {
                    val o = originalBytes.toList()
                    val r = rewrittenBytes.toList()
                    "${fixture.name}: byte mismatch orig=${o.size} rewritten=${r.size} " +
                        "sep=${detectLineSeparator(content).replace("\r", "\\r").replace("\n", "\\n")} " +
                        "blocks=${document.blocks.size} " +
                        "firstDiff=${o.zip(r).indexOfFirst { (a, b) -> a != b }}"
                }
            }
        }
    }
}

private fun decodeUtf8WithOptionalBom(bytes: ByteArray): Pair<ByteArray, String> {
    val bom =
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(0, 3)
        } else {
            ByteArray(0)
        }
    val text =
        if (bom.isEmpty()) {
            bytes.toString(Charsets.UTF_8)
        } else {
            bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
    return bom to text
}
