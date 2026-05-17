package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import com.lomo.ui.theme.TypographyScales
import org.intellij.markdown.MarkdownElementTypes

/*
 * Test Contract:
 * - Unit under test: modern markdown heading text resolution.
 * - Behavior focus: heading rendering must resolve visible heading text from ATX and Setext nodes instead of handing the raw heading wrapper to the annotator.
 * - Observable outcomes: produced annotated heading text content.
 * - Red phase: Fails before the fix because the modern heading renderer delegates the full heading node to the annotator, which can leave memo-card headings visually blank even though the block itself is present.
 * - Excludes: Compose tree rendering, Android TextView internals, and third-party markdown parser implementation details beyond the exposed AST shape.
 */
class ModernMarkdownHeadingTextResolverTest : UiComponentsFunSpec() {
    private val defaultScales = TypographyScales()

    init {
        test("atx heading produces visible text without heading markers") {
        val content = "# 一级标题"
        val root = parseModernMarkdownDocument(content)
        val headingNode = root.children.first { it.type == MarkdownElementTypes.ATX_1 }

        val resolved =
            buildModernMarkdownHeadingAnnotatedText(
                content = content,
                node = headingNode,
                style = Typography().headlineSmall,
                tokenSpec =
                    createModernMarkdownTokenSpec(
                        Typography(),
                        linkColor = Color(0xFF3366FF),
                        scales = defaultScales,
                    ),
            )

        (resolved.text.trim()) shouldBe ("一级标题")
        (resolved.text.contains("#")) shouldBe false
        (resolved.text.isNotBlank()) shouldBe true
        }
    }

    init {
        test("setext heading keeps inline text and strips underline syntax") {
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
                tokenSpec =
                    createModernMarkdownTokenSpec(
                        Typography(),
                        linkColor = Color(0xFF3366FF),
                        scales = defaultScales,
                    ),
            )

        (resolved.text.contains("粗体")) shouldBe true
        (resolved.text.contains("标题")) shouldBe true
        (resolved.text.contains("=")) shouldBe false
        }
    }
}
