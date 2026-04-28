/*
 * Test Contract:
 * - Unit under test: MarkdownKnownTagFilter
 * - Behavior focus: tag stripping and erasure from markdown text and AST nodes.
 * - Observable outcomes: inline tags removed, tree nodes erased, edge cases (code blocks, nested formatting).
 * - Red phase: Not applicable - test-only update; no production behavior change.
 * - Excludes: full markdown rendering pipeline.
 */
package com.lomo.ui.component.markdown

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownKnownTagFilterTest {
    @Test
    fun `eraseKnownTags skips links and code while pruning tag only paragraph`() {
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

        val paragraphs = collectNodes(root.node).filterIsInstance<Paragraph>()
        val renderedText = collectNodes(root.node).filterIsInstance<Text>().mapNotNull { it.literal }.joinToString("\n")
        val codeLiterals = collectNodes(root.node).filterIsInstance<FencedCodeBlock>().map { it.literal.orEmpty() }
        val linkDestinations = collectNodes(root.node).filterIsInstance<Link>().map { it.destination.orEmpty() }

        assertEquals(2, paragraphs.size)
        assertTrue(renderedText.contains("plain body line"))
        assertFalse(renderedText.contains("#todo"))
        assertFalse(renderedText.contains("#work"))
        assertTrue(codeLiterals.any { it.contains("#todo") })
        assertTrue(linkDestinations.any { it.contains("#todo") })
    }

    @Test
    fun `stripInlineTags removes only known inline tags`() {
        val result =
            MarkdownKnownTagFilter.stripInlineTags(
                input = "Meeting with C# team #work and #todo today.",
                tags = listOf("work", "todo"),
            )

        assertTrue(result.contains("C#"))
        assertFalse(result.contains("#work"))
        assertFalse(result.contains("#todo"))
    }

    private fun collectNodes(root: Node): List<Node> {
        val nodes = mutableListOf<Node>()

        fun traverse(node: Node) {
            nodes += node
            var child = node.firstChild
            while (child != null) {
                val next = child.next
                traverse(child)
                child = next
            }
        }

        traverse(root)
        return nodes
    }

    private fun parseMarkdown(content: String): ImmutableNode = ImmutableNode(commonMarkParser.parse(content))

    private companion object {
        val commonMarkParser: Parser =
            Parser
                .builder()
                .extensions(
                    listOf(
                        StrikethroughExtension.create(),
                        TablesExtension.create(),
                        AutolinkExtension.create(),
                        TaskListItemsExtension.create(),
                    ),
                ).includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build()
    }
}
