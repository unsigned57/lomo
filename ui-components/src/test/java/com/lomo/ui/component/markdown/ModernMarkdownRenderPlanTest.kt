package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown render planning and task-list interaction support.
 * - Behavior focus: the unified modern backend preserves memo-specific behavior for block counting, image gallery grouping, known-tag stripping, and todo line mapping.
 * - Observable outcomes: sanitized markdown content, total block count, visible render-item kinds, task source lines, and effective checked states.
 * - Red phase: Fails before the fix because the modern backend lacks the extracted planning/interaction logic needed to support todo, known-tag stripping, and collapsed memo block behavior.
 * - Excludes: Compose tree structure, image loading, animation details, and third-party markdown parser internals beyond the exposed AST.
 */
class ModernMarkdownRenderPlanTest {
    @Test
    fun `render plan counts top level blocks while grouping consecutive image paragraphs into one visible gallery`() {
        val plan =
            createModernMarkdownRenderPlan(
                content =
                    """
                    intro

                    ![a](one.png)

                    ![b](two.png)

                    outro
                    """.trimIndent(),
                maxVisibleBlocks = 2,
                knownTagsToStrip = emptyList(),
            )

        assertEquals(4, plan.totalBlocks)
        assertEquals(2, plan.items.size)
        assertTrue(plan.items.first() is ModernMarkdownRenderItem.Block)
        assertTrue(plan.items[1] is ModernMarkdownRenderItem.Gallery)
        val gallery = plan.items[1] as ModernMarkdownRenderItem.Gallery
        assertEquals(listOf("one.png", "two.png"), gallery.images.map { it.destination })
    }

    @Test
    fun `task list presentation derives line based overrides from the modern ast`() {
        val content =
            """
            - [ ] todo one
            - [x] done two
            """.trimIndent()
        val root = parseModernMarkdownDocument(content)
        val listNode = root.children.first { it.type == MarkdownElementTypes.UNORDERED_LIST }
        val listItems = listNode.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }

        val firstPresentation =
            resolveModernTaskListPresentation(
                content = content,
                listItemNode = listItems[0],
                todoOverrides = mapOf(1 to false),
            )
        val secondPresentation =
            resolveModernTaskListPresentation(
                content = content,
                listItemNode = listItems[1],
                todoOverrides = mapOf(1 to false),
            )

        assertTrue(firstPresentation.isTask)
        assertEquals(0, firstPresentation.sourceLine)
        assertFalse(firstPresentation.effectiveChecked)

        assertTrue(secondPresentation.isTask)
        assertEquals(1, secondPresentation.sourceLine)
        assertFalse(secondPresentation.effectiveChecked)
    }

    @Test
    fun `known tag sanitizing strips plain text tags but preserves headings links and code`() {
        val sanitized =
            sanitizeModernMarkdownKnownTags(
                content =
                    """
                    # Heading #todo
                    
                    [jump #todo](https://example.com/#todo)
                    
                    ```kotlin
                    val raw = "#todo"
                    ```
                    
                    plain body #todo line
                    """.trimIndent(),
                tags = listOf("todo"),
            )

        assertTrue(sanitized.contains("# Heading #todo"))
        assertTrue(sanitized.contains("[jump #todo](https://example.com/#todo)"))
        assertTrue(sanitized.contains("val raw = \"#todo\""))
        assertTrue(sanitized.contains("plain body line"))
        assertFalse(sanitized.contains("plain body #todo line"))
    }

    @Test
    fun `render plan prunes blank paragraph blocks left behind after known tag stripping`() {
        val plan =
            createModernMarkdownRenderPlan(
                content =
                    """
                    #todo #work

                    body line
                    """.trimIndent(),
                knownTagsToStrip = listOf("todo", "work"),
            )

        assertEquals(1, plan.totalBlocks)
        assertEquals(1, plan.items.size)
        assertTrue(plan.content.contains("body line"))
    }
}
