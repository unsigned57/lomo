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
 * - Unit under test: buildMarkdownIrPresentationPlan.
 * - Owning layer: ui-components; layout grouping of Rust-owned Render IR.
 * - Priority tier: P1.
 * - Capability: consecutive pictures form one swipeable group despite layout whitespace.
 * Scenarios:
 * - Given image paragraphs containing soft breaks, hard breaks or whitespace, when projected,
 *   then image order is preserved in one gallery.
 * - Given text or audio between pictures, when projected, then that content remains separate.
 * - Observable outcomes: presentation item kinds and ordered image destinations.
 * - TDD proof: RED sees a Block instead of Gallery for newline-separated imported pictures.
 * - Excludes: Markdown parsing, image decoding and gesture delivery.
 * Test Change Justification:
 * Reason category: requested recording placement fix.
 * Old behavior/assertion being replaced: a mixed paragraph was required to have one display block.
 * Why old assertion is no longer correct: audio divides a paragraph into ordered display blocks.
 * Coverage preserved by: the same exact three ordered picture/player group assertions.
 * Why this is not fitting the test to the implementation: source media order is still the oracle.
 * TDD proof: audio-red.log fails both recording-order cases; audio-green.log exposes the obsolete single-block assumption.

 */
class MarkdownImageGroupingTest : UiComponentsFunSpec() {
    init {
        test(
            "given pictures separated by line breaks and whitespace when projected then one gallery retains their order",
        ) {
            val plan = plan(
                paragraph(image("first.png"), MarkdownRenderInline.SoftBreak(span), image("second.png")),
                paragraph(
                    MarkdownRenderInline.Text(span, "  "),
                    image("third.png"),
                    MarkdownRenderInline.HardBreak(span),
                ),
            )

            plan.items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Gallery>()
                .images.map { it.destination } shouldBe listOf("first.png", "second.png", "third.png")
        }

        test("given text and audio separating pictures when projected then their content stays outside galleries") {
            val plan = plan(
                paragraph(image("first.png")),
                paragraph(MarkdownRenderInline.Text(span, "caption")),
                paragraph(image("voice.m4a", MediaClass.AUDIO)),
                paragraph(image("second.png")),
            )

            plan.items.size shouldBe 4
            plan.items.forEach { it.shouldBeInstanceOf<MarkdownIrPresentationItem.Block>() }
        }

        test("given a caption with multiple pictures when projected then the paragraph media retains one image group") {
            val display = plan(
                paragraph(
                    MarkdownRenderInline.Text(span, "caption"),
                    MarkdownRenderInline.SoftBreak(span),
                    image("first.png"),
                    MarkdownRenderInline.SoftBreak(span),
                    image("second.png"),
                ),
            ).items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                .display.shouldBeInstanceOf<MarkdownIrBlockDisplay.Paragraph>()

            display.text.text.trim() shouldBe "caption"
            display.mediaGroups.single().map { it.destination } shouldBe listOf("first.png", "second.png")
        }

        test(
            "given pictures and audio in one paragraph when projected then the player separates ordered image groups",
        ) {
            val groups = plan(
                paragraph(
                    image("first.png"),
                    image("second.png"),
                    image("voice.m4a", MediaClass.AUDIO),
                    image("third.png"),
                    image("fourth.png"),
                ),
            ).items.flatMap { item ->
                when (item) {
                    is MarkdownIrPresentationItem.Gallery -> listOf(item.images)

                    is MarkdownIrPresentationItem.Block ->
                        item.display.shouldBeInstanceOf<MarkdownIrBlockDisplay.Paragraph>().mediaGroups
                }
            }

            groups.map { group -> group.map { it.destination } } shouldBe listOf(
                listOf("first.png", "second.png"),
                listOf("voice.m4a"),
                listOf("third.png", "fourth.png"),
            )
        }
    }
}

private val span = MarkdownSourceSpan(0uL, 1uL)

private fun image(path: String, mediaClass: MediaClass = MediaClass.IMAGE) =
    MarkdownRenderInline.Image(span, path, null, path, mediaClass)

private fun paragraph(vararg inlines: MarkdownRenderInline) = MarkdownRenderBlock.Paragraph(span, inlines.toList())

private fun plan(vararg blocks: MarkdownRenderBlock) = buildMarkdownIrPresentationPlan(
    MarkdownRenderDocument(1uL, "", emptyList(), emptyList(), blocks.toList()),
)
