package com.lomo.ui.component.markdown

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: display typed underline with nested Markdown styles; owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given typed underline containing bold text, when projected, then text has both underline and bold spans.
 * - Given typed underline, when projected to plain text, then no HTML delimiters appear.
 * Observable outcomes: annotated text, span ranges and style values.
 * TDD proof: the canonical regression in underline-red.log produced literal <u> tags;
 * underline-green.log proves the owner emits formatting; this spec locks its presentation.
 * Evidence: target/lomo/reports/manual/media-toolbar-search/.
 * Excludes: Markdown parsing, native conversion and physical-device pixels.
 */
class MarkdownUnderlinePresentationTest : UiComponentsFunSpec() {
    init {
        test("given typed underline with bold text when displayed then both styles cover the visible text") {
            val span = MarkdownSourceSpan(0uL, 20uL)
            val inlines = listOf(
                MarkdownRenderInline.Underline(
                    span,
                    listOf(
                        MarkdownRenderInline.Strong(
                            span,
                            listOf(MarkdownRenderInline.Text(span, "focus")),
                        ),
                    ),
                ),
            )
            val displayed = inlines.toAnnotatedText()
            displayed.text shouldBe "focus"
            inlines.toPlainText() shouldBe "focus"
            displayed.spanStyles.any {
                it.start == 0 && it.end == 5 && it.item.textDecoration == TextDecoration.Underline
            } shouldBe true
            displayed.spanStyles.any {
                it.start == 0 && it.end == 5 && it.item.fontWeight == FontWeight.Bold
            } shouldBe true
        }
    }
}
