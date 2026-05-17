/*
 * Test Contract:
 * - Unit under test: ShareCardDisplayFormatterTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ShareCardDisplayFormatterTest.
 * - Boundary: boundary and edge cases for ShareCardDisplayFormatterTest.
 * - Failure: failure and error scenarios for ShareCardDisplayFormatterTest.
 * - Must-not-happen: invariants are never violated for ShareCardDisplayFormatterTest.
 *
 * - Behavior focus: test behavioral outcomes of ShareCardDisplayFormatterTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.presentation.sharecard

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: ShareCardDisplayFormatter
 * - Behavior focus: share-card tag normalization and plain-text body formatting contracts.
 * - Observable outcomes: rendered tag list, placeholder substitution, markdown-to-plain-text output.
 * - Excludes: bitmap rendering, typography/layout, and Android UI integration.
 */
class ShareCardDisplayFormatterTest : AppFunSpec() {
    private val formatter = ShareCardDisplayFormatter()

    init {
        test("formatTagsForDisplay trims hashes deduplicates truncates and caps count") {
            val tags =
                listOf(
                    "  #Android  ",
                    "#Android",
                    "",
                    "   ",
                    "#Compose",
                    "VeryLongTagNameThatShouldBeTrimmed",
                    "#Sync",
                    "#Git",
                    "#Offline",
                    "#Extra",
                    "#Ignored",
                )

            val result = formatter.formatTagsForDisplay(tags)

            (result) shouldBe (listOf(
                    "Android",
                    "Compose",
                    "VeryLongTagNameTha",
                    "Sync",
                    "Git",
                    "Offline",
                ))
        }
    }

    init {
        test("formatBodyText converts markdown constructs to stable share-card text") {
            val result =
                formatter.formatBodyText(
                    bodyText =
                        """
                        # Title
                        ### Subtitle
                        - bullet item
                        1. ordered item
                        > quoted
                        `inline`
                        ~~removed~~
                        ![voice](voice_01.m4a)
                        [Image]
                        [Image: cover.png]
                        """.trimIndent(),
                    audioPlaceholder = "[Audio]",
                    imagePlaceholder = "[Photo]",
                    imageNamedPlaceholderPattern = "[Photo: %s]",
                )

            (result) shouldBe ("""
                ✦ Title
                • Subtitle
                • bullet item
                • ordered item
                │ quoted
                「inline」
                removed
                [Audio]
                [Photo]
                [Photo: cover.png]
                """.trimIndent())
        }
    }

    init {
        test("formatBodyText indents code blocks and falls back when image pattern is invalid") {
            val result =
                formatter.formatBodyText(
                    bodyText =
                        """
                        ```kotlin
                        val x = 1
                        ```

                        [Image: screenshot.png]
                        """.trimIndent(),
                    audioPlaceholder = "[Audio]",
                    imagePlaceholder = "[Photo]",
                    imageNamedPlaceholderPattern = "%q",
                )

            (result) shouldBe ("""
                val x = 1

                %q screenshot.png
                """.trimIndent())
        }
    }

}
