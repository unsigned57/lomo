/*
 * Behavior Contract:
 * - Unit under test: buildMarkdownIrPresentationPlan.
 * - Owning layer: ui-components presentation policy; Markdown semantics remain lomo-workspace.
 * - Priority tier: P1.
 * - Capability: build layout items from typed domain Render IR without receiving or parsing source text.
 *
 * Scenarios:
 * - Given typed nested quote/list/link/task nodes, when a presentation plan is built, then hierarchy,
 *   link destination, and task action span remain available for Compose interaction.
 * - Given consecutive image-only typed paragraphs, when a plan is built, then presentation groups
 *   them as a gallery using typed image destinations.
 * - Given a visible-block limit, when a plan is built, then the typed item sequence is bounded
 *   without truncating or rewriting the RenderDocument.
 *
 * Observable outcomes:
 * - Presentation item kinds, nested typed nodes, link destination, action span, and gallery images.
 *
 * TDD proof:
 * - RED before the fix: ui-components only exposes createModernMarkdownRenderPlan(content), which
 *   requires source text and the JetBrains parser; no typed-IR-only presentation entry exists.
 *
 * Excludes:
 * - Markdown recognition, data/native conversion, production renderer wiring, and media loading.
 */
package com.lomo.ui.component.markdown

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownRenderTableCell
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MarkdownIrPresentationPlanTest : UiComponentsFunSpec() {
    init {
        test("given nested typed IR when plan is built then interaction facts and hierarchy are preserved") {
            val link =
                MarkdownRenderInline.Link(
                    sourceSpan = span(2uL, 12uL),
                    destination = "https://lomo.app",
                    title = null,
                    inlines = listOf(MarkdownRenderInline.Text(span(3uL, 7uL), "Lomo")),
                )
            val task =
                MarkdownRenderListItem(
                    sourceSpan = span(13uL, 32uL),
                    actionSpan = span(15uL, 18uL),
                    checked = false,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.BlockQuote(
                                sourceSpan = span(19uL, 32uL),
                                blocks =
                                    listOf(
                                        MarkdownRenderBlock.Paragraph(
                                            sourceSpan = span(21uL, 32uL),
                                            inlines =
                                                listOf(
                                                    MarkdownRenderInline.Text(
                                                        span(21uL, 32uL),
                                                        "nested task",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                )
            val document =
                document(
                    sourceLength = 32uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(span(0uL, 12uL), listOf(link)),
                            MarkdownRenderBlock.ListBlock(
                                sourceSpan = span(13uL, 32uL),
                                ordered = false,
                                startNumber = 1uL,
                                items = listOf(task),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.totalBlocks shouldBe 2
            val paragraph =
                plan.items[0]
                    .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                    .block.shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            paragraph.inlines.single().shouldBeInstanceOf<MarkdownRenderInline.Link>().destination shouldBe
                "https://lomo.app"
            val list =
                plan.items[1]
                    .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                    .block.shouldBeInstanceOf<MarkdownRenderBlock.ListBlock>()
            list.items.single().actionSpan shouldBe span(15uL, 18uL)
            list.items.single().blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.BlockQuote>()
        }

        test("given consecutive typed image paragraphs when plan is built then they form a gallery") {
            val first = image(destination = "images/one.png", start = 0uL, end = 8uL)
            val second = image(destination = "images/two.png", start = 9uL, end = 17uL)
            val document =
                document(
                    sourceLength = 24uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(span(0uL, 8uL), listOf(first)),
                            MarkdownRenderBlock.Paragraph(span(9uL, 17uL), listOf(second)),
                            MarkdownRenderBlock.Paragraph(
                                span(18uL, 24uL),
                                listOf(MarkdownRenderInline.Text(span(18uL, 24uL), "outro")),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.totalBlocks shouldBe 3
            plan.items[0].shouldBeInstanceOf<MarkdownIrPresentationItem.Gallery>().images shouldBe
                listOf(first, second)
            plan.items[1].shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
        }

        test("given a visible block limit when plan is built then items are bounded without mutating the document") {
            val blocks =
                listOf(
                    MarkdownRenderBlock.Paragraph(
                        span(0uL, 1uL),
                        listOf(MarkdownRenderInline.Text(span(0uL, 1uL), "a")),
                    ),
                    MarkdownRenderBlock.Paragraph(
                        span(2uL, 3uL),
                        listOf(MarkdownRenderInline.Text(span(2uL, 3uL), "b")),
                    ),
                )
            val document = document(sourceLength = 3uL, blocks = blocks)

            val plan = buildMarkdownIrPresentationPlan(document = document, maxVisibleBlocks = 1)

            plan.totalBlocks shouldBe 2
            plan.items.size shouldBe 1
            document.blocks shouldBe blocks
        }

        test("given heading table code and wiki image IR when plan is built then typed blocks remain unparsed") {
            val wikiImage =
                MarkdownRenderInline.Image(
                    sourceSpan = span(40uL, 55uL),
                    destination = "wiki-cover.png",
                    title = null,
                    altText = "cover",
                )
            val document =
                document(
                    sourceLength = 80uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Heading(
                                sourceSpan = span(0uL, 8uL),
                                level = 2u,
                                inlines = listOf(MarkdownRenderInline.Text(span(0uL, 8uL), "Heading")),
                            ),
                            MarkdownRenderBlock.CodeBlock(
                                sourceSpan = span(9uL, 20uL),
                                language = "kt",
                                literal = "val x = 1",
                            ),
                            MarkdownRenderBlock.Table(
                                sourceSpan = span(21uL, 39uL),
                                header =
                                    listOf(
                                        MarkdownRenderTableCell(
                                            sourceSpan = span(21uL, 25uL),
                                            inlines = listOf(MarkdownRenderInline.Text(span(21uL, 25uL), "Col")),
                                        ),
                                    ),
                                rows = emptyList(),
                            ),
                            MarkdownRenderBlock.Paragraph(span(40uL, 55uL), listOf(wikiImage)),
                            MarkdownRenderBlock.Paragraph(
                                span(56uL, 80uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Tag(span(56uL, 62uL), "topic"),
                                        MarkdownRenderInline.Reminder(span(63uL, 80uL), "@2026-01-01"),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.totalBlocks shouldBe 5
            plan.items[0]
                .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                .block
                .shouldBeInstanceOf<MarkdownRenderBlock.Heading>()
                .level shouldBe 2u
            plan.items[1]
                .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                .block
                .shouldBeInstanceOf<MarkdownRenderBlock.CodeBlock>()
                .literal shouldBe "val x = 1"
            plan.items[2]
                .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                .block
                .shouldBeInstanceOf<MarkdownRenderBlock.Table>()
            // Single image paragraph may present as gallery or block depending on policy; destination stays typed.
            val imageNodes =
                plan.items.flatMap { item ->
                    when (item) {
                        is MarkdownIrPresentationItem.Gallery -> item.images
                        is MarkdownIrPresentationItem.Block ->
                            (item.block as? MarkdownRenderBlock.Paragraph)
                                ?.inlines
                                ?.filterIsInstance<MarkdownRenderInline.Image>()
                                .orEmpty()
                    }
                }
            imageNodes.map { it.destination } shouldBe listOf("wiki-cover.png")
        }
    }
}

private fun document(
    sourceLength: ULong,
    blocks: List<MarkdownRenderBlock>,
): MarkdownRenderDocument =
    MarkdownRenderDocument(
        sourceByteLength = sourceLength,
        plainText = "",
        tagNames = emptyList(),
        attachmentDestinations = emptyList(),
        blocks = blocks,
    )

private fun image(
    destination: String,
    start: ULong,
    end: ULong,
): MarkdownRenderInline.Image =
    MarkdownRenderInline.Image(
        sourceSpan = span(start, end),
        destination = destination,
        title = null,
        altText = destination,
    )

private fun span(start: ULong, end: ULong): MarkdownSourceSpan =
    MarkdownSourceSpan(startByte = start, endByte = end)
