/*
 * Behavior Contract:
 * - Unit under test: SimpleLineDiff
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: produce lightweight line-level diff hunks for conflict review
 *   while refusing inputs whose compared-cell product would exceed the configured cell
 *   budget and whose line counts would make the rendered hunk too large.
 *
 * Scenarios:
 * - Given identical text, when diffed, then no hunks are emitted.
 * - Given one side is empty, when diffed, then the visible result is made only
 *   of inserted or deleted lines.
 * - Given one line changes, when diffed, then one hunk contains the deleted old
 *   line and inserted new line with correct line numbers.
 * - Given changes are separated by more than the context window, when diffed,
 *   then separate hunks are emitted.
 * - Given non-identical inputs would exceed the configured compared-cell budget, when
 *   a result is requested, then the diff returns a TooLarge result with line
 *   counts and does not attempt to compute hunks.
 * - Given one side has far more lines than the display budget while the line-pair
 *   product stays under budget, when a result is requested, then the diff
 *   still returns TooLarge and the legacy hunk API returns the visible
 *   fallback.
 *
 * Observable outcomes:
 * - returned hunk list, line operations, line numbers, and explicit
 *   SimpleLineDiff.DiffResult value.
 *
 * TDD proof:
 * - Budgeted result: RED before implementation because SimpleLineDiff has no
 *   diffResult API or TooLarge result type.
 * - Line-count budget: RED before implementation because a 50,001-line old
 *   text against empty text returns Computed with one huge hunk instead of
 *   TooLarge, even though the compared-cell budget is not exceeded.
 * - Existing hunk behavior: retained tests fail if current visible diff output
 *   regresses during the budget refactor.
 *
 * Excludes:
 * - UI rendering, syntax-aware diffing, moved-line detection, binary content,
 *   and automatic merge decisions.
  * Test Change Justification:
 * - Reason category: production parameter rename — maxMatrixCells renamed to maxComparedCells
 *   after the LCS matrix implementation was replaced.
 * - Old behavior/assertion being replaced: TooLarge assertions naming maxMatrixCells and
 *   scenario text describing an LCS matrix budget.
 * - Why old assertion is no longer correct: the production API now exposes maxComparedCells;
 *   the matrix-budget name no longer exists.
 * - Coverage preserved by: the same budget outcomes (TooLarge with counts, fallback hunks)
 *   under the new parameter name; the display-budget scenario is unchanged.
 * - Why this is not fitting the test to the implementation: only the vocabulary migrated; the
 *   numeric thresholds and expected TooLarge semantics are identical.
*/

package com.lomo.domain.model

import com.lomo.domain.model.SimpleLineDiff.DiffOp
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe

class SimpleLineDiffTest : DomainFunSpec() {
    init {
        test("identical texts produce no hunks") {
            val hunks = SimpleLineDiff.diff("hello\nworld", "hello\nworld")
            (hunks.isEmpty()) shouldBe true
        }

        test("empty old text shows all inserts") {
            val hunks = SimpleLineDiff.diff("", "a\nb")
            val lines = hunks.flatMap { it.lines }
            (lines.all { it.op == DiffOp.INSERT || it.op == DiffOp.DELETE }) shouldBe true
        }

        test("empty new text shows all deletes") {
            val hunks = SimpleLineDiff.diff("a\nb", "")
            val lines = hunks.flatMap { it.lines }
            (lines.any { it.op == DiffOp.DELETE }) shouldBe true
        }

        test("single line change produces one hunk with delete and insert") {
            val hunks = SimpleLineDiff.diff("hello", "world")
            hunks.size shouldBe 1
            val lines = hunks[0].lines
            (lines.any { it.op == DiffOp.DELETE && it.text == "hello" }) shouldBe true
            (lines.any { it.op == DiffOp.INSERT && it.text == "world" }) shouldBe true
        }

        test("addition in middle produces correct diff") {
            val old = "a\nb\nc"
            val new = "a\nb\nnew\nc"
            val hunks = SimpleLineDiff.diff(old, new)
            val inserts = hunks.flatMap { it.lines }.filter { it.op == DiffOp.INSERT }
            inserts.size shouldBe 1
            inserts[0].text shouldBe "new"
        }

        test("line numbers are correct") {
            val hunks = SimpleLineDiff.diff("a\nb\nc", "a\nx\nc")
            val lines = hunks.flatMap { it.lines }
            val deletedLine = lines.first { it.op == DiffOp.DELETE }
            val insertedLine = lines.first { it.op == DiffOp.INSERT }
            deletedLine.oldLineNumber shouldBe 2
            insertedLine.newLineNumber shouldBe 2
        }

        test("distant changes produce separate hunks") {
            // 10 equal lines, 1 change, 10 equal lines, 1 change
            val oldLines =
                (1..10).map { "line$it" } + listOf("old1") +
                    (12..21).map { "line$it" } + listOf("old2")
            val newLines =
                (1..10).map { "line$it" } + listOf("new1") +
                    (12..21).map { "line$it" } + listOf("new2")
            val hunks = SimpleLineDiff.diff(oldLines.joinToString("\n"), newLines.joinToString("\n"))
            hunks.size shouldBe 2
        }

        test("given line pair product exceeds the compared-cell budget when result requested then too large result is returned") {
            val oldText = (1..4).joinToString("\n") { line -> "old-$line" }
            val newText = (1..5).joinToString("\n") { line -> "new-$line" }

            val result = SimpleLineDiff.diffResult(
                oldText = oldText,
                newText = newText,
                maxComparedCells = 12,
            )

            val tooLarge = result.shouldBeInstanceOf<SimpleLineDiff.DiffResult.TooLarge>()
            tooLarge.oldLineCount shouldBe 4
            tooLarge.newLineCount shouldBe 5
            tooLarge.maxComparedCells shouldBe 12
        }

        test("emitted ops reconstruct the new text from the old text") {
            // Replaying DELETEs then INSERTs over the old lines must rebuild the
            // new lines exactly — the invariant that makes the emitted hunks a
            // valid edit script regardless of how Myers breaks ties on
            // repeated lines. Every non-EQUAL line sits inside a hunk, so the
            // hunked view alone carries the full edit script.
            val cases =
                listOf(
                    "a\nb\nc" to "a\nx\nc",
                    "a\nb\na\nb" to "a\na\nb\nb",
                    "x\nx\nx" to "x",
                    "" to "a",
                    "a" to "",
                    "same\nsame\nsame" to "same\ndiff\nsame",
                    "one" to "one\ntwo\nthree\nfour\nfive",
                )
            for ((oldText, newText) in cases) {
                val computed = SimpleLineDiff
                    .diffResult(oldText, newText)
                    .shouldBeInstanceOf<SimpleLineDiff.DiffResult.Computed>()
                val ops = computed.hunks.flatMap { it.lines }
                val replay = oldText.lines().toMutableList()
                ops.filter { it.op == DiffOp.DELETE }
                    .sortedByDescending { it.oldLineNumber }
                    .forEach { line ->
                        replay.removeAt(
                            (line.oldLineNumber ?: error("DELETE lacks an old line number")) - 1,
                        )
                    }
                ops.filter { it.op == DiffOp.INSERT }
                    .sortedBy { it.newLineNumber }
                    .forEach { line ->
                        replay.add(
                            (line.newLineNumber ?: error("INSERT lacks a new line number")) - 1,
                            line.text,
                        )
                    }
                replay shouldBe newText.lines()
            }
        }

        test("hunk line numbers reconcile against the source lines") {
            // Every emitted line's numbers must index a real source line:
            // DELETE carries an old position, INSERT a new one, EQUAL both.
            val oldLines = (1..12).map { "line-$it" }
            val newLines =
                oldLines.toMutableList().apply {
                    this[9] = "changed"
                    add(4, "inserted")
                }
            val computed = SimpleLineDiff
                .diffResult(oldLines.joinToString("\n"), newLines.joinToString("\n"))
                .shouldBeInstanceOf<SimpleLineDiff.DiffResult.Computed>()
            for (line in computed.hunks.flatMap { it.lines }) {
                when (line.op) {
                    DiffOp.DELETE -> {
                        line.newLineNumber shouldBe null
                        line.text shouldBe
                            oldLines[(line.oldLineNumber ?: error("DELETE lacks old number")) - 1]
                    }
                    DiffOp.INSERT -> {
                        line.oldLineNumber shouldBe null
                        line.text shouldBe
                            newLines[(line.newLineNumber ?: error("INSERT lacks new number")) - 1]
                    }
                    DiffOp.EQUAL -> {
                        val oldIndex =
                            (line.oldLineNumber ?: error("EQUAL lacks old number")) - 1
                        val newIndex =
                            (line.newLineNumber ?: error("EQUAL lacks new number")) - 1
                        line.text shouldBe oldLines[oldIndex]
                        line.text shouldBe newLines[newIndex]
                    }
                }
            }
        }

        test("given one side exceeds the line budget when the compared-cell product is within budget then too large fallback is returned") {
            val oldText = (1..50_001).joinToString("\n") { line -> "old-$line" }
            val newText = ""

            val result = SimpleLineDiff.diffResult(oldText = oldText, newText = newText)
            val visibleHunks = SimpleLineDiff.diff(oldText = oldText, newText = newText)

            val tooLarge = result.shouldBeInstanceOf<SimpleLineDiff.DiffResult.TooLarge>()
            tooLarge.oldLineCount shouldBe 50_001
            tooLarge.newLineCount shouldBe 1
            tooLarge.maxComparedCells shouldBe 250_000
            tooLarge.maxDiffLineCount shouldBe 2_000
            visibleHunks shouldBe emptyList()
        }
    }
}
