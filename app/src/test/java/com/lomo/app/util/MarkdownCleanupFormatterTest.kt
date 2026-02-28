package com.lomo.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownCleanupFormatterTest {
    @Test
    fun `stripForPlainText normalizes markdown tokens`() {
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

        assertEquals(
            """
            Title
            bold link
            ☐ todo
            ☑ done
            [Image]
            [Image: photo.jpg]
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `collapseSpacing compacts spaces and blank lines`() {
        val input = "  a   b\n\n\nc  "

        assertEquals("a b\n\nc", MarkdownCleanupFormatter.collapseSpacing(input))
        assertEquals(" a b\n\nc ", MarkdownCleanupFormatter.collapseSpacing(input, trim = false))
    }
}
