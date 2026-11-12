package com.lomo.ui.component.markdown

import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * Capability: recordings retain their insertion position; owner: ui-components; priority: P1.
 * Scenarios:
 * - Given text, audio, and text separated by soft breaks, when planned, then three blocks retain source order.
 * - Given audio inside emphasis, when planned, then surrounding text remains on each side.
 * Observable outcomes: ordered rendered text and media destinations.
 * TDD proof: RED/GREEN: ./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.markdown.MarkdownAudioPositionTest'.
 * Excludes: audio playback, Markdown parsing, and Compose pixels.
 */
class MarkdownAudioPositionTest : UiComponentsFunSpec() {
    init {
        for (nested in listOf(false, true)) {
            test(
                "given ${if (nested) "formatted" else "plain"} audio between text when projected then its insertion order survives",
            ) {
                val span = MarkdownSourceSpan(0uL, 1uL)
                val audio = MarkdownRenderInline.Image(span, "voice/a.m4a", null, "audio", MediaClass.AUDIO)
                val embed = if (nested) MarkdownRenderInline.Strong(span, listOf(audio)) else audio
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
                                embed,
                                MarkdownRenderInline.SoftBreak(span),
                                MarkdownRenderInline.Text(span, "after"),
                            ),
                        ),
                    ),
                )
                val displays = buildMarkdownIrPresentationPlan(document).items.map {
                    it.shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().display
                        .shouldBeInstanceOf<MarkdownIrBlockDisplay.Paragraph>()
                }
                displays.map { it.text.text to it.images.map { image -> image.destination } } shouldBe listOf(
                    "before" to emptyList(),
                    "" to listOf("voice/a.m4a"),
                    "after" to emptyList(),
                )
            }
        }
    }
}
