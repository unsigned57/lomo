package com.lomo.ui.component.markdown

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.intellij.markdown.MarkdownElementTypes

/*
 * Behavior Contract:
 * - Unit under test: createModernMarkdownRenderPlan + sanitizeModernMarkdownKnownTags + resolveModernTaskListPresentation
 * - Owning layer: ui-components (markdown)
 * - Priority tier: P2
 * - Capability: a Markdown task-list item stays a task (renders a checkbox) after reminder tokens and
 *   known tags are sanitized out of its line, so toggling a todo that carries a reminder/tag never
 *   degrades into a plain bullet showing literal "[ ]".
 *
 * Scenarios:
 * - Given an unchecked task item with a trailing reminder token, when the render plan sanitizes the reminder, then the item is still a task.
 * - Given an unchecked task item whose only content is a reminder token, when sanitized, then the item is still a task.
 * - Given an unchecked task item whose only content is a known tag, when sanitized, then the item is still a task.
 * - Given checked task items in the same shapes, when sanitized, then they remain tasks (symmetry with unchecked).
 * - Given plain task items without tokens, when planned, then they remain tasks (baseline lock).
 *
 * Observable outcomes:
 * - resolveModernTaskListPresentation(...).isTask for the first list item of the sanitized plan.
 *
 * TDD proof:
 * - Fails before the fix because sanitization ran SPACE_BEFORE_PUNCTUATION cleanup over the GFM
 *   CHECK_BOX token and deleted the space inside "[ ]", producing "[]", which the GFM parser no longer
 *   recognizes as a task; isTask became false for unchecked items whenever a reminder/tag was stripped.
 *
 * Excludes:
 * - Checkbox click handling, effectiveChecked overlay state, and Compose rendering.
 */
class ModernMarkdownTaskListSanitizationTest : FunSpec({
    fun firstListItemIsTask(
        raw: String,
        tags: List<String> = emptyList(),
    ): Boolean {
        val plan = createModernMarkdownRenderPlan(content = raw, knownTagsToStrip = tags)
        val listNode =
            plan.root.children.firstOrNull {
                it.type == MarkdownElementTypes.UNORDERED_LIST || it.type == MarkdownElementTypes.ORDERED_LIST
            } ?: return false
        val firstItem = listNode.children.first { it.type == MarkdownElementTypes.LIST_ITEM }
        return resolveModernTaskListPresentation(plan.content, firstItem, emptyMap()).isTask
    }

    test("given an unchecked task with a trailing reminder when sanitized then it stays a task") {
        firstListItemIsTask("- [ ] buy milk @2026-05-30-09:00") shouldBe true
    }

    test("given an unchecked task whose only content is a reminder when sanitized then it stays a task") {
        firstListItemIsTask("- [ ] @2026-05-30-09:00") shouldBe true
    }

    test("given an unchecked task whose only content is a known tag when sanitized then it stays a task") {
        firstListItemIsTask("- [ ] #shopping", tags = listOf("shopping")) shouldBe true
    }

    test("given checked tasks in reminder and tag shapes when sanitized then they stay tasks") {
        firstListItemIsTask("- [x] pay rent @2026-05-30-09:00") shouldBe true
        firstListItemIsTask("- [x] @2026-05-30-09:00") shouldBe true
        firstListItemIsTask("- [x] #shopping", tags = listOf("shopping")) shouldBe true
    }

    test("given plain tasks without tokens when planned then they stay tasks") {
        firstListItemIsTask("- [ ] buy milk") shouldBe true
        firstListItemIsTask("- [x] buy milk") shouldBe true
    }
})
