package com.lomo.ui.component.markdown

import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * Capability: paragraph media extraction preserves only meaningful text lines; owner: ui-components; priority: P1.
 * Scenarios:
 * - Given image-only lines among text, when extracting media, then text has no orphan blank lines.
 * - Given leading/trailing image lines, when extracting media, then text has no leading/trailing break.
 * - Given formatted image-only content, when extracting media, then formatting cannot retain a blank row.
 * - Given multiline formatting across image and text lines, when extracting, then paragraph-wide line boundaries remain correct.
 * Observable outcomes: paragraph display text and extracted media destinations.
 * TDD proof: RED/GREEN: ./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.markdown.MarkdownParagraphMediaProjectionTest'.
 * Excludes: Markdown parsing, Coil loading and Compose drawing.
 */
class MarkdownParagraphMediaProjectionTest : UiComponentsFunSpec() {
    init {
        val span = MarkdownSourceSpan(0uL, 1uL)
        fun text(value: String) = MarkdownRenderInline.Text(span, value)
        fun image() = MarkdownRenderInline.Image(span, "images/test.png", null, "picture", MediaClass.IMAGE)
        fun display(inlines: List<MarkdownRenderInline>) = buildMarkdownIrBlockDisplay(
            MarkdownRenderBlock.Paragraph(span, inlines),
        ).shouldBeInstanceOf<MarkdownIrBlockDisplay.Paragraph>()
        test("given an image-only middle line when split then text lines remain adjacent") {
            val result = display(
                listOf(
                    text("before"),
                    MarkdownRenderInline.SoftBreak(span),
                    image(),
                    MarkdownRenderInline.SoftBreak(span),
                    text("after"),
                ),
            )
            result.text.text shouldBe "before\nafter"
            result.images.map { it.destination } shouldBe listOf("images/test.png")
        }
        test("given boundary image lines when split then no empty boundary line survives") {
            val result = display(
                listOf(
                    image(),
                    MarkdownRenderInline.SoftBreak(span),
                    text("body"),
                    MarkdownRenderInline.HardBreak(span),
                    image(),
                ),
            )
            result.text.text shouldBe "body"
        }
        test("given an image line inside multiline emphasis when split then nested text lines remain adjacent") {
            val result = display(
                listOf(
                    MarkdownRenderInline.Strong(
                        span,
                        listOf(
                            text("before"),
                            MarkdownRenderInline.SoftBreak(span),
                            image(),
                            MarkdownRenderInline.SoftBreak(span),
                            text("after"),
                        ),
                    ),
                ),
            )
            result.text.text shouldBe "before\nafter"
            result.images.size shouldBe 1
        }
        test("given nested breaks beside outer text when extracting then a meaningful line break survives") {
            val result = display(
                listOf(
                    text("before "),
                    MarkdownRenderInline.Strong(
                        span,
                        listOf(
                            image(),
                            MarkdownRenderInline.SoftBreak(span),
                            text("after"),
                        ),
                    ),
                ),
            )
            result.text.text shouldBe "before \nafter"
            result.images.size shouldBe 1
        }
        test("given an emphasized image line when split then formatting leaves no text row") {
            val result = display(
                listOf(
                    MarkdownRenderInline.Strong(span, listOf(image())),
                    MarkdownRenderInline.SoftBreak(span),
                    text("body"),
                ),
            )
            result.text.text shouldBe "body"
            result.images.map { it.destination } shouldBe listOf("images/test.png")
        }
    }
}
