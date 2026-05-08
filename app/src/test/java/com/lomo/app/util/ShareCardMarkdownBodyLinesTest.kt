package com.lomo.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: share-card Markdown body line builder.
 * - Behavior focus: generated share-card images must apply common Markdown semantics instead of flattening Markdown to plain body text.
 * - Observable outcomes: share body line type, text, checked task state, table rows, image slots, and inline text style ranges.
 * - Red phase: Fails before the fix because share-card rendering uses regex cleanup that drops Markdown semantics for headings, strikethrough, tables, and parser-resolved image syntax.
 * - Excludes: bitmap pixel rendering, Android resource lookup, image decoding, and share intent/file-provider wiring.
 */
class ShareCardMarkdownBodyLinesTest {
    @Test
    fun `markdown share body lines preserve common markdown semantics for bitmap rendering`() {
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

        assertEquals(ShareBodyLineType.Heading, lines[0].type)
        assertEquals(1, lines[0].headingLevel)
        assertEquals("Title", lines[0].text)

        val paragraph = lines.first { it.text.contains("removed") }
        assertTrue(paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Bold })
        assertTrue(paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Strikethrough })

        val quote = lines.first { it.type == ShareBodyLineType.Quote }
        assertEquals("│ quoted text", quote.text)

        assertEquals("☑ done", lines.first { it.text.contains("done") }.text)
        assertEquals("• plain item", lines.first { it.text.contains("plain item") }.text)

        val tableLines = lines.filter { it.type == ShareBodyLineType.Table }
        assertEquals(listOf("Name | Status", "Lomo | ready"), tableLines.map { it.text })

        val image = lines.single { it.type == ShareBodyLineType.Image }
        assertEquals(0, image.imageIndex)
    }

    @Test
    fun `markdown share body lines preserve quote markers and supported html tags for bitmap rendering`() {
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
        assertEquals(
            """
            │ quoted
            underlined and bold
            italic, removed
            next
            """.trimIndent(),
            quote.text,
        )
        assertTrue(quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Underline })
        assertTrue(quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Bold })
        assertTrue(quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Italic })
        assertTrue(quote.inlineStyles.any { it.kind == ShareInlineStyleKind.Strikethrough })
    }
}
