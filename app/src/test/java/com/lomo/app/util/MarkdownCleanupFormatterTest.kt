/*
 * Test Contract:
 * - Unit under test: MarkdownCleanupFormatterTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for MarkdownCleanupFormatterTest.
 * - Boundary: boundary and edge cases for MarkdownCleanupFormatterTest.
 * - Failure: failure and error scenarios for MarkdownCleanupFormatterTest.
 * - Must-not-happen: invariants are never violated for MarkdownCleanupFormatterTest.
 *
 * - Behavior focus: test behavioral outcomes of MarkdownCleanupFormatterTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.util

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
