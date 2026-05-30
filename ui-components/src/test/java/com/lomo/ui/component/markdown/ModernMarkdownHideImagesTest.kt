package com.lomo.ui.component.markdown

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.intellij.markdown.MarkdownElementTypes

/*
 * Behavior Contract:
 * - Unit under test: createModernMarkdownRenderPlan + resolveModernTaskListPresentation
 * - Owning layer: ui-components (markdown)
 * - Priority tier: P2
 * - Capability: preserve original markdown content and character offsets during parsing when rendering with
 *   hideImages = true, ensuring checklist task checklist items resolve to exact correct line indices in the database write-back.
 *
 * Scenarios:
 * - Given markdown content with intro text, an image, and a checklist item, when planned and resolved, then the checklist item resolves to line index 2 (its original index).
 * - Given markdown content with a checklist item, a gallery of multiple images, and another checklist item, when planned and resolved, then the checklist items resolve to their exact original line indices (line 0 and line 4).
 *
 * Observable outcomes:
 * - resolveModernTaskListPresentation(plan.content, node, emptyMap()).sourceLine
 */
class ModernMarkdownHideImagesTest : FunSpec({
    test("given content with an image and checklist item then checklist resolves to correct original line index") {
        val content = """
            intro text
            ![image](img.png)
            - [ ] todo item
        """.trimIndent()

        val plan = createModernMarkdownRenderPlan(content = content, knownTagsToStrip = emptyList())
        val listNode = plan.root.children.first {
            it.type == MarkdownElementTypes.UNORDERED_LIST
        }
        val listItem = listNode.children.first { it.type == MarkdownElementTypes.LIST_ITEM }

        val presentation = resolveModernTaskListPresentation(plan.content, listItem, emptyMap())
        presentation.isTask shouldBe true
        // 0-indexed: line 0 is "intro text", line 1 is "![image](img.png)", line 2 is "- [ ] todo item"
        presentation.sourceLine shouldBe 2
    }

    test("given content with a checklist item, a gallery of multiple images, and another checklist item then checklist resolves to correct original line index") {
        val content = """
            - [ ] first todo
            ![img1](one.png)
            ![img2](two.png)
            
            - [ ] second todo
        """.trimIndent()

        val plan = createModernMarkdownRenderPlan(content = content, knownTagsToStrip = emptyList())
        val listItems = mutableListOf<org.intellij.markdown.ast.ASTNode>()
        fun collectListItems(node: org.intellij.markdown.ast.ASTNode) {
            if (node.type == MarkdownElementTypes.LIST_ITEM) {
                listItems.add(node)
            }
            node.children.forEach { collectListItems(it) }
        }
        collectListItems(plan.root)
        listItems.size shouldBe 2

        val firstItem = listItems[0]
        val secondItem = listItems[1]

        val firstPresentation = resolveModernTaskListPresentation(plan.content, firstItem, emptyMap())
        firstPresentation.isTask shouldBe true
        firstPresentation.sourceLine shouldBe 0

        val secondPresentation = resolveModernTaskListPresentation(plan.content, secondItem, emptyMap())
        secondPresentation.isTask shouldBe true
        // 0-indexed: line 0 is "- [ ] first todo", line 1 is "![img1](one.png)", line 2 is "![img2](two.png)", line 3 is "", line 4 is "- [ ] second todo"
        secondPresentation.sourceLine shouldBe 4
    }
})
