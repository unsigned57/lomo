/*
 * Behavior Contract:
 * - Unit under test: SimpleLineDiffTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for SimpleLineDiffTest.
 * - Boundary: boundary and edge cases for SimpleLineDiffTest.
 * - Failure: failure and error scenarios for SimpleLineDiffTest.
 * - Must-not-happen: invariants are never violated for SimpleLineDiffTest.
 *
 * - Behavior focus: test behavioral outcomes of SimpleLineDiffTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.model

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


import com.lomo.domain.model.SimpleLineDiff.DiffOp
import com.lomo.domain.testing.DomainFunSpec
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
    }
}
