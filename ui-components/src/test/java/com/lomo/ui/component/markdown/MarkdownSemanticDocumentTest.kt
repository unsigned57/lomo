package com.lomo.ui.component.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Markdown semantic document parsing.
 * - Behavior focus: common CommonMark/GFM constructs must be exposed as stable display semantics shared by app rendering and share-card rendering.
 * - Observable outcomes: parsed block kinds, quote/list/task/table/image destinations, and inline strikethrough/link semantics.
 * - Red phase: Fails before the fix because common Markdown semantics are split across render-only code and share-card regex cleanup, with no shared parser for quote/table/reference image/strikethrough behavior.
 * - Excludes: Compose tree rendering, bitmap pixel output, third-party parser internals, and unsupported extensions such as math or Mermaid.
 */
class MarkdownSemanticDocumentTest {
    @Test
    fun `common markdown document exposes stable semantic blocks and inline styles`() {
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
        assertEquals(1, heading.level)
        assertEquals("Title", heading.plainText)

        val quote = document.blocks[1] as MarkdownSemanticBlock.BlockQuote
        val quoteParagraph = quote.blocks.single() as MarkdownSemanticBlock.Paragraph
        assertEquals("quoted bold and link", quoteParagraph.plainText)
        assertTrue(quoteParagraph.inlines.any { it is MarkdownSemanticInline.Strong })
        assertTrue(quoteParagraph.inlines.any { it is MarkdownSemanticInline.Link })

        val list = document.blocks[2] as MarkdownSemanticBlock.ListBlock
        assertEquals(false, list.ordered)
        assertEquals(true, list.items[0].checked)
        assertEquals("done", list.items[0].plainText)
        assertEquals(null, list.items[1].checked)

        val table = document.blocks[3] as MarkdownSemanticBlock.Table
        assertEquals(listOf("Name", "Status"), table.header.map { it.plainText })
        assertEquals("Lomo", table.rows.single()[0].plainText)
        assertTrue(table.rows.single()[1].inlines.any { it is MarkdownSemanticInline.Strikethrough })

        val imageParagraph = document.blocks[4] as MarkdownSemanticBlock.Paragraph
        val image = imageParagraph.inlines.single() as MarkdownSemanticInline.Image
        assertEquals("cover", image.altText)
        assertEquals("images/cover.png", image.destination)
    }
}
