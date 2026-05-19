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

/*
 * Behavior Contract:
 * - Unit under test: share-card Markdown body line builder.
 * - Behavior focus: generated share-card images must apply common Markdown semantics instead of flattening Markdown to plain body text.
 * - Observable outcomes: share body line type, text, checked task state, table rows, image slots, and inline text style ranges.
 * - TDD proof: Fails before the fix because share-card rendering uses regex cleanup that drops Markdown semantics for headings, strikethrough, tables, and parser-resolved image syntax.
 * - Excludes: bitmap pixel rendering, Android resource lookup, image decoding, and share intent/file-provider wiring.
 */
class ShareCardMarkdownBodyLinesTest : AppFunSpec() {
    init {
        test("markdown share body lines preserve common markdown semantics for bitmap rendering") {
            val processed =
                preprocessShareCardContent(
                    content =
                        """
                        # Title

                        Paragraph with **bold** and ~~removed~~ text.

                        > quoted text

                        - [x] done
                        - plain item

                        | Name | Status |
                        | --- | --- |
                        | Lomo | ready |

                        ![cover](images/cover.png)
                        """.trimIndent(),
                    hasImages = true,
                )

            val lines =
                buildMarkdownShareBodyLines(
                    bodyText = processed.contentForProcessing,
                    imagePlaceholder = "[Image]",
                )

            (lines[0].type) shouldBe (ShareBodyLineType.Heading)
            (lines[0].headingLevel) shouldBe (1)
            (lines[0].text) shouldBe ("Title")

            val paragraph = lines.first { it.text.contains("removed") }
            ((paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Bold })) shouldBe true
            ((paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Strikethrough })) shouldBe true

            val quote = lines.first { it.type == ShareBodyLineType.Quote }
            (quote.text) shouldBe ("│ quoted text")

            (lines.first { it.text.contains("done") }.text) shouldBe ("☑ done")
            (lines.first { it.text.contains("plain item") }.text) shouldBe ("• plain item")

            val tableLines = lines.filter { it.type == ShareBodyLineType.Table }
            (tableLines.map { it.text }) shouldBe (listOf("Name | Status", "Lomo | ready"))

            val image = lines.single { it.type == ShareBodyLineType.Image }
            (image.imageIndex) shouldBe (0)
        }
    }

    init {
        test("markdown share body lines preserve quote markers and supported html tags for bitmap rendering") {
            val lines =
                buildMarkdownShareBodyLines(
                    bodyText =
                        """
                        > quoted
                        > <u>underlined</u> and <strong>bold</strong>
                        > <em>italic</em>, <del>removed</del><br>next
                        """.trimIndent(),
                    imagePlaceholder = "[Image]",
                )

            val quote = lines.single { it.type == ShareBodyLineType.Quote }
            (quote.text) shouldBe ("""
                │ quoted
                underlined and bold
                italic, removed
                next
                """.trimIndent())
            ((quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Underline })) shouldBe true
            ((quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Bold })) shouldBe true
            ((quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Italic })) shouldBe true
            ((quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Strikethrough })) shouldBe true
        }
    }

}
