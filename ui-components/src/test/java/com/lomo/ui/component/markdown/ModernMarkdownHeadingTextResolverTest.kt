package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import org.intellij.markdown.MarkdownElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown heading text resolution.
 * - Behavior focus: heading rendering must resolve visible heading text from ATX and Setext nodes instead of handing the raw heading wrapper to the annotator.
 * - Observable outcomes: produced annotated heading text content.
 * - Red phase: Fails before the fix because the modern heading renderer delegates the full heading node to the annotator, which can leave memo-card headings visually blank even though the block itself is present.
 * - Excludes: Compose tree rendering, Android TextView internals, and third-party markdown parser implementation details beyond the exposed AST shape.
 */
class ModernMarkdownHeadingTextResolverTest {
    @Test
    fun `atx heading produces visible text without heading markers`() {
        val content = "# 一级标题"
        val root = parseModernMarkdownDocument(content)
        val headingNode = root.children.first { it.type == MarkdownElementTypes.ATX_1 }

        val resolved =
            buildModernMarkdownHeadingAnnotatedText(
                content = content,
                node = headingNode,
                style = Typography().headlineSmall,
                tokenSpec = createModernMarkdownTokenSpec(Typography(), linkColor = Color(0xFF3366FF)),
            )

        assertEquals("一级标题", resolved.text.trim())
        assertFalse(resolved.text.contains("#"))
        assertTrue(resolved.text.isNotBlank())
    }

    @Test
    fun `setext heading keeps inline text and strips underline syntax`() {
        val content =
            """
            **粗体** 标题
            ============
            """.trimIndent()
        val root = parseModernMarkdownDocument(content)
        val headingNode = root.children.first { it.type == MarkdownElementTypes.SETEXT_1 }

        val resolved =
            buildModernMarkdownHeadingAnnotatedText(
                content = content,
                node = headingNode,
                style = Typography().headlineSmall,
                tokenSpec = createModernMarkdownTokenSpec(Typography(), linkColor = Color(0xFF3366FF)),
            )

        assertTrue(resolved.text.contains("粗体"))
        assertTrue(resolved.text.contains("标题"))
        assertFalse(resolved.text.contains("="))
    }
}
