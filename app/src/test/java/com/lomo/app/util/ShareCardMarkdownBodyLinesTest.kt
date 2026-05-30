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

        test("markdown share body lines preserve ordered list numbering correctly") {
            val content =
                """
                1. first item
                2. second item
                3. third item
                """.trimIndent()

            val lines =
                buildMarkdownShareBodyLines(
                    bodyText = content,
                    imagePlaceholder = "[Image]",
                )

            lines.size shouldBe 3
            lines[0].text shouldBe "1. first item"
            lines[1].text shouldBe "2. second item"
            lines[2].text shouldBe "3. third item"
        }

        /*
         * Behavior Contract:
         * - Capability: Premium share card URL and Geo link pre-processing.
         * - Given: A memo content with bare URLs, bare geo coordinates, or GFM autolinks.
         * - When: `linkifyBareUrlsAndGeoUris` is invoked.
         * - Then:
         *   1. Bare URLs (e.g. http://..., https://..., or www....) are linkified into standard Markdown links.
         *   2. Bare geo coordinates (e.g. geo:...) are linkified.
         *   3. URLs that are already part of existing markdown links are NOT double-linkified.
         */
        test("linkifyBareUrlsAndGeoUris correctly transforms bare URLs and geo coordinates without double linkification") {
            val content = "Visit https://google.com and www.lomo.app and geo:31.2304,121.4737 or [Google](https://google.com) or [https://google.com](https://google.com)"
            val result = linkifyBareUrlsAndGeoUris(content)
            result shouldBe "Visit [https://google.com](https://google.com) and [www.lomo.app](https://www.lomo.app) and [geo:31.2304,121.4737](geo:31.2304,121.4737?z=10) or [Google](https://google.com) or [https://google.com](https://google.com)"
        }

        test("markdown share body lines preserve url link style and highlight style from text with highlight and url") {
            val processed = preprocessShareCardContent(
                content = "Visit https://example.com and ==important==",
                hasImages = false,
            )
            val linkified = linkifyBareUrlsAndGeoUris(processed.contentForProcessing)
            val lines = buildMarkdownShareBodyLines(
                bodyText = linkified,
                imagePlaceholder = "[Image]",
            )

            val paragraph = lines.single { it.type == ShareBodyLineType.Paragraph }
            paragraph.text shouldBe "Visit https://example.com and important"
            (paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Link }) shouldBe true
            (paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Highlight }) shouldBe true
        }

        test("markdown share body lines support nested highlight and link style ranges simultaneously") {
            val processed = preprocessShareCardContent(
                content = "==[site](https://example.com)==",
                hasImages = false,
            )
            val lines = buildMarkdownShareBodyLines(
                bodyText = processed.contentForProcessing,
                imagePlaceholder = "[Image]",
            )

            val paragraph = lines.single { it.type == ShareBodyLineType.Paragraph }
            paragraph.text shouldBe "site"

            val highlightRange = paragraph.inlineStyles.single { it.kind == ShareInlineStyleKind.Highlight }
            highlightRange.start shouldBe 0
            highlightRange.end shouldBe 4

            val linkRange = paragraph.inlineStyles.single { it.kind == ShareInlineStyleKind.Link }
            linkRange.start shouldBe 0
            linkRange.end shouldBe 4
        }
    }
}
