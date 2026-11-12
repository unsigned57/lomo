package com.lomo.ui.component.markdown

import androidx.compose.ui.text.style.TextDecoration
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * Capability: completed task text keeps Markdown styling; owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given a completed task, when projected, then its text receives a strikethrough.
 * - Given an unchecked task, when projected, then its text has no completion decoration.
 * - Given an underlined completed task, when projected, then both decorations survive.
 * Observable outcomes: annotated display text and original action coordinates.
 * TDD proof: RED/GREEN ./kotlin test --include-module=ui-components
 * --include-classes=com.lomo.ui.component.markdown.MarkdownTaskPresentationTest (ui-bugs/markdown-*.log).
 * Excludes: Markdown parsing, checkbox pixel geometry and durable task mutations.
 */
class MarkdownTaskPresentationTest : UiComponentsFunSpec() {
    init {
        test("given completed and open tasks when projecting then only completed text is struck") {
            for (checked in listOf(true, false)) {
                val display = taskDisplay(checked, underline = false)
                display.actionSpan shouldBe MarkdownSourceSpan(2uL, 5uL)
                val text = display.blocks.single().shouldBeInstanceOf<MarkdownIrBlockDisplay.Paragraph>().text
                text.text shouldBe "task"
                text.spanStyles.any {
                    it.item.textDecoration?.contains(
                        TextDecoration.LineThrough,
                    ) == true
                } shouldBe checked
            }
        }
        test("given an underlined completed task when projecting then underline and strike both survive") {
            val text = taskDisplay(true, underline = true).blocks.single()
                .shouldBeInstanceOf<MarkdownIrBlockDisplay.Paragraph>().text
            text.spanStyles.any {
                it.item.textDecoration?.contains(TextDecoration.Underline) == true &&
                    it.item.textDecoration?.contains(TextDecoration.LineThrough) == true
            } shouldBe true
        }
    }
}

private fun taskDisplay(checked: Boolean, underline: Boolean): MarkdownIrListItemDisplay {
    val span = MarkdownSourceSpan(0uL, 12uL)
    val text = MarkdownRenderInline.Text(span, "task")
    val inline = if (underline) MarkdownRenderInline.Underline(span, listOf(text)) else text
    val block = MarkdownRenderBlock.ListBlock(
        span,
        false,
        1uL,
        listOf(
            MarkdownRenderListItem(
                span,
                MarkdownSourceSpan(2uL, 5uL),
                checked,
                listOf(MarkdownRenderBlock.Paragraph(span, listOf(inline))),
            ),
        ),
    )
    return buildMarkdownIrBlockDisplay(block).shouldBeInstanceOf<MarkdownIrBlockDisplay.ListBlock>().items.single()
}
