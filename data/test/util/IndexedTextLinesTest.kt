package com.lomo.data.util

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



import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: IndexedTextLines
 * - Behavior focus: Index text lines for FTS insertion.
 * - Observable outcomes: Correct line offsets.
 * - TDD proof: Verified by asserting bad offsets.
 * - Excludes: none.
 */
class IndexedTextLinesTest : DataFunSpec() {
    init {
        test("indexed text lines mirrors kotlin lines across newline styles") { `indexed text lines mirrors kotlin lines across newline styles`() }

        test("indexed text lines preserves empty input contract") { `indexed text lines preserves empty input contract`() }

        test("find destructive memo block works with indexed text lines") { `find destructive memo block works with indexed text lines`() }
    }


    private fun `indexed text lines mirrors kotlin lines across newline styles`() {
        val content = "first\r\nsecond\nthird\rfourth\n"

        IndexedTextLines.of(content) shouldBe content.lines()
    }

    private fun `indexed text lines preserves empty input contract`() {
        IndexedTextLines.of("") shouldBe listOf("")
    }

    private fun `find destructive memo block works with indexed text lines`() {
        val content =
            """
            - 09:00 keep
            still keep
            - 10:00 target
            target body
            - 11:00 stay
            """.trimIndent()

        findDestructiveMemoBlock(
                lines = IndexedTextLines.of(content),
                rawContent = "- 10:00 target\ntarget body",
                memoId = null,
            ) shouldBe (2 to 3)
    }
}
