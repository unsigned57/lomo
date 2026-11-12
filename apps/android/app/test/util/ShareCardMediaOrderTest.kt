package com.lomo.app.util

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: share cards preserve inline media order without duplicate labels; owner: app; priority: P1.
 * Scenarios:
 * - Given text, an image, and text in one paragraph, when shared, then the image occurs once between text runs.
 * - Given an emphasized image, when shared, then the image is extracted without an image placeholder.
 * - Given audio between text lines, when shared, then its marker stays between those lines.
 * Observable outcomes: ordered share body line types and text.
 * TDD proof: RED/GREEN: ./kotlin test --include-module=app --include-classes='com.lomo.app.util.ShareCardMediaOrderTest'.
 * Excludes: bitmap encoding and Android sharing intents.
 */
class ShareCardMediaOrderTest : AppFunSpec() {
    init {
        val span = MarkdownSourceSpan(0uL, 1uL)
        for (nested in listOf(false, true)) {
            test(
                "given a ${if (nested) "formatted" else "plain"} image between text when shared then it appears once in source order",
            ) {
                val image = MarkdownRenderInline.Image(span, "images/a.png", null, "image", MediaClass.IMAGE)
                val embed = if (nested) MarkdownRenderInline.Strong(span, listOf(image)) else image
                val document = MarkdownRenderDocument(
                    1uL,
                    "before image after",
                    emptyList(),
                    emptyList(),
                    listOf(
                        MarkdownRenderBlock.Paragraph(
                            span,
                            listOf(
                                MarkdownRenderInline.Text(span, "before"),
                                MarkdownRenderInline.SoftBreak(span),
                                embed,
                                MarkdownRenderInline.SoftBreak(span),
                                MarkdownRenderInline.Text(span, "after"),
                            ),
                        ),
                    ),
                )
                val lines = buildMarkdownShareBodyLines(document, "[image]")
                lines.map { it.type } shouldBe listOf(
                    ShareBodyLineType.Paragraph,
                    ShareBodyLineType.Image,
                    ShareBodyLineType.Paragraph,
                )
                lines.filter { it.type != ShareBodyLineType.Image }.map { it.text } shouldBe listOf("before", "after")
            }
        }
        test("given audio between text lines when shared then the audio marker retains its position") {
            val document = MarkdownRenderDocument(
                1uL,
                "before after",
                emptyList(),
                emptyList(),
                listOf(
                    MarkdownRenderBlock.Paragraph(
                        span,
                        listOf(
                            MarkdownRenderInline.Text(span, "before"),
                            MarkdownRenderInline.SoftBreak(span),
                            MarkdownRenderInline.Image(span, "voice/a.m4a", null, "audio", MediaClass.AUDIO),
                            MarkdownRenderInline.SoftBreak(span),
                            MarkdownRenderInline.Text(span, "after"),
                        ),
                    ),
                ),
            )
            buildMarkdownShareBodyLines(
                document,
                "[image]",
            ).map { it.text } shouldBe listOf("before", "[Audio]", "after")
        }
    }
}
