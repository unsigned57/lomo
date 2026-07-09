/*
 * Test Contract:
 * - Unit under test: MarkdownKnownTagFilter
 * - Behavior focus: tag stripping from markdown content while preserving links/code and pruning tag-only paragraphs after reparsing.
 * - Observable outcomes: known inline tags are removed from visible paragraph text, code/link destinations still keep literal tags, and the sanitized AST no longer exposes the tag-only paragraph.
 * - Red phase: Covered by the CommonMark removal refactor introducing AST-backed reparsing.
 * - Excludes: full markdown rendering pipeline.
 */

package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MarkdownKnownTagFilterTest : UiComponentsFunSpec() {
    init {
        test("eraseKnownTags skips links and code while pruning tag only paragraph") {
        val root =
            MarkdownKnownTagFilter.eraseKnownTags(
                root =
                    parseMarkdown(
                        """
                        #todo #work
                        [jump](https://example.com/#todo)
                        ```kotlin
                        val raw = "#todo"
                        ```
                        plain body #todo line
                        """.trimIndent(),
                    ),
                tags = listOf("todo", "work"),
            )

        val blocks = parseMarkdownSemanticDocument(root.content).blocks
        val paragraphs = blocks.filterIsInstance<MarkdownSemanticBlock.Paragraph>()
        val codeBlocks = blocks.filterIsInstance<MarkdownSemanticBlock.CodeBlock>()
        val link =
            paragraphs
                .first()
                .inlines
                .filterIsInstance<MarkdownSemanticInline.Link>()
                .single()

        (paragraphs.size) shouldBe (2)
        (paragraphs.first().plainText) shouldBe ("jump")
        (paragraphs.last().plainText) shouldBe ("plain body line")
        (paragraphs.any { "#todo" in it.plainText || "#work" in it.plainText }) shouldBe false
        (link.destination) shouldBe ("https://example.com/#todo")
        (codeBlocks.single().literal.contains("#todo")) shouldBe true
        }
    }

    init {
        test("stripInlineTags removes only known inline tags") {
        val result =
            MarkdownKnownTagFilter.stripInlineTags(
                input = "Meeting with C# team #work and #todo today.",
                tags = listOf("work", "todo"),
            )

        (result.contains("C#")) shouldBe true
        (result.contains("#work")) shouldBe false
        (result.contains("#todo")) shouldBe false
        }
    }

    private fun parseMarkdown(content: String): ImmutableNode =
        ImmutableNode(
            node = parseModernMarkdownDocument(content),
            content = content,
        )
}
