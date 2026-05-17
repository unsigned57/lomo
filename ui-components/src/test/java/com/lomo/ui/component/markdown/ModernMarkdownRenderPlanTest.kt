package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.intellij.markdown.MarkdownElementTypes

/*
 * Test Contract:
 * - Unit under test: modern markdown render planning and task-list interaction support.
 * - Behavior focus: the unified modern backend preserves memo-specific behavior for block counting, image gallery grouping, known-tag stripping, and todo line mapping.
 * - Observable outcomes: sanitized markdown content, total block count, visible render-item kinds, task source lines, and effective checked states.
 * - Red phase: Fails before the fix because the modern backend lacks the extracted planning/interaction logic needed to support todo, known-tag stripping, and collapsed memo block behavior.
 * - Excludes: Compose tree structure, image loading, animation details, and third-party markdown parser internals beyond the exposed AST.
 */
class ModernMarkdownRenderPlanTest : UiComponentsFunSpec() {
    init {
        test("render plan counts top level blocks while grouping consecutive image paragraphs into one visible gallery") {
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

        (plan.totalBlocks) shouldBe (4)
        (plan.items.size) shouldBe (2)
        plan.items.first().shouldBeInstanceOf<ModernMarkdownRenderItem.Block>()
        val gallery = plan.items[1].shouldBeInstanceOf<ModernMarkdownRenderItem.Gallery>()
        (gallery.images.map { it.destination }) shouldBe (listOf("one.png", "two.png"))
        }
    }

    init {
        test("task list presentation derives line based overrides from the modern ast") {
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

        (firstPresentation.isTask) shouldBe true
        (firstPresentation.sourceLine) shouldBe (0)
        (firstPresentation.effectiveChecked) shouldBe false

        (secondPresentation.isTask) shouldBe true
        (secondPresentation.sourceLine) shouldBe (1)
        (secondPresentation.effectiveChecked) shouldBe false
        }
    }

    init {
        test("known tag sanitizing strips plain text tags but preserves headings links and code") {
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

        (sanitized.content.contains("# Heading #todo")) shouldBe true
        (sanitized.content.contains("[jump #todo](https://example.com/#todo)")) shouldBe true
        (sanitized.content.contains("val raw = \"#todo\"")) shouldBe true
        (sanitized.content.contains("plain body line")) shouldBe true
        (sanitized.content.contains("plain body #todo line")) shouldBe false
        (sanitized.reusableRoot) shouldBe (null)
        }
    }

    init {
        test("known tag sanitizing returns reusable ast when content stays unchanged") {
        val sanitized =
            sanitizeModernMarkdownKnownTags(
                content =
                    """
                    # Heading #todo

                    [jump #todo](https://example.com/#todo)
                    """.trimIndent(),
                tags = listOf("todo"),
            )

        (sanitized.content) shouldBe ("""
            # Heading #todo

            [jump #todo](https://example.com/#todo)
            """.trimIndent())
        (sanitized.reusableRoot != null) shouldBe true
        }
    }

    init {
        test("render plan prunes blank paragraph blocks left behind after known tag stripping") {
        val plan =
            createModernMarkdownRenderPlan(
                content =
                    """
                    #todo #work

                    body line
                    """.trimIndent(),
                knownTagsToStrip = listOf("todo", "work"),
            )

        (plan.totalBlocks) shouldBe (1)
        (plan.items.size) shouldBe (1)
        (plan.content.contains("body line")) shouldBe true
        }
    }
}
