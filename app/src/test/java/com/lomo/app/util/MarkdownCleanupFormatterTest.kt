/*
 * Behavior Contract:
 * - Unit under test: MarkdownCleanupFormatterTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for MarkdownCleanupFormatterTest.
 * - Boundary: boundary and edge cases for MarkdownCleanupFormatterTest.
 * - Failure: failure and error scenarios for MarkdownCleanupFormatterTest.
 * - Must-not-happen: invariants are never violated for MarkdownCleanupFormatterTest.
 *
 * - Behavior focus: test behavioral outcomes of MarkdownCleanupFormatterTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.util

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


import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class MarkdownCleanupFormatterTest : AppFunSpec() {
    init {
        test("stripForPlainText normalizes markdown tokens") {
            val input =
                """
                # Title
                **bold** [link](https://example.com)
                - [ ] todo
                - [x] done
                ![img](a.png)
                ![[photo.jpg]]
                """.trimIndent()

            val result = MarkdownCleanupFormatter.stripForPlainText(input)

            (result) shouldBe ("""
                Title
                bold link
                ☐ todo
                ☑ done
                [Image]
                [Image: photo.jpg]
                """.trimIndent())
        }
    }

    init {
        test("collapseSpacing compacts spaces and blank lines") {
            val input = "  a   b\n\n\nc  "

            (MarkdownCleanupFormatter.collapseSpacing(input)) shouldBe ("a b\n\nc")
            (MarkdownCleanupFormatter.collapseSpacing(input, trim = false)) shouldBe (" a b\n\nc ")
        }
    }

}
