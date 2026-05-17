package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: Markdown semantic document parsing.
 * - Behavior focus: common CommonMark/GFM constructs must be exposed as stable display semantics shared by app rendering and share-card rendering.
 * - Observable outcomes: parsed block kinds, quote/list/task/table/image destinations, and inline strikethrough/link semantics.
 * - Red phase: Fails before the fix because common Markdown semantics are split across render-only code and share-card regex cleanup, with no shared parser for quote/table/reference image/strikethrough behavior.
 * - Excludes: Compose tree rendering, bitmap pixel output, third-party parser internals, and unsupported extensions such as math or Mermaid.
 */
class MarkdownSemanticDocumentTest : UiComponentsFunSpec() {
    init {
        test("common markdown document exposes stable semantic blocks and inline styles") {
        val document =
            parseMarkdownSemanticDocument(
                """
                # Title

                > quoted **bold** and [link][home]

                - [x] done
                - plain item

                | Name | Status |
                | --- | --- |
                | Lomo | ~~ready~~ |

                ![cover][cover]

                [home]: https://example.com
                [cover]: images/cover.png "Cover"
                """.trimIndent(),
            )

        val heading = document.blocks[0] as MarkdownSemanticBlock.Heading
        (heading.level) shouldBe (1)
        (heading.plainText) shouldBe ("Title")

        val quote = document.blocks[1] as MarkdownSemanticBlock.BlockQuote
        val quoteParagraph = quote.blocks.single() as MarkdownSemanticBlock.Paragraph
        (quoteParagraph.plainText) shouldBe ("quoted bold and link")
        (quoteParagraph.inlines.any { it is MarkdownSemanticInline.Strong }) shouldBe true
        (quoteParagraph.inlines.any { it is MarkdownSemanticInline.Link }) shouldBe true

        val list = document.blocks[2] as MarkdownSemanticBlock.ListBlock
        (list.ordered) shouldBe (false)
        (list.items[0].checked) shouldBe (true)
        (list.items[0].plainText) shouldBe ("done")
        (list.items[1].checked) shouldBe (null)

        val table = document.blocks[3] as MarkdownSemanticBlock.Table
        (table.header.map { it.plainText }) shouldBe (listOf("Name", "Status"))
        (table.rows.single()[0].plainText) shouldBe ("Lomo")
        (table.rows.single()[1].inlines.any { it is MarkdownSemanticInline.Strikethrough }) shouldBe true

        val imageParagraph = document.blocks[4] as MarkdownSemanticBlock.Paragraph
        val image = imageParagraph.inlines.single() as MarkdownSemanticInline.Image
        (image.altText) shouldBe ("cover")
        (image.destination) shouldBe ("images/cover.png")
        }
    }
}
