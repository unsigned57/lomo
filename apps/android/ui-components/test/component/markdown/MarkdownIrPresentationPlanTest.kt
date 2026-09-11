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
 * - Given inline tags and reminders with MEMO_CARD policy, when presentation plan is built, then inline tags and reminders are stripped.
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
/*
 * Test Change Justification:
 * - Reason category: feature extension for MEMO_CARD presentation policy and tag/reminder stripping.
 * - Old behavior/assertion being replaced: presentation plan rendered all inline nodes identically regardless of card policy.
 * - Why old assertion is no longer correct: memo cards present tags and reminders in header/footer metadata pills, so duplicating them in body text causes visual clutter.
 * - Coverage preserved by: tests verifying tag/reminder removal, whitespace normalization, and task item retention under MEMO_CARD policy.
 * - Why this is not fitting the test to the implementation: assertions verify design requirement of clean card body presentation.
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
            val nestedItem =
                MarkdownRenderListItem(
                    sourceSpan = span(0uL, 20uL),
                    actionSpan = null,
                    checked = null,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(2uL, 20uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(span(2uL, 7uL), "Item "),
                                        MarkdownRenderInline.Link(
                                            sourceSpan = span(7uL, 20uL),
                                            destination = "https://example.com",
                                            title = null,
                                            inlines =
                                                listOf(
                                                    MarkdownRenderInline.Text(span(8uL, 12uL), "link"),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                )
            val document =
                document(
                    sourceLength = 60uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.BlockQuote(
                                sourceSpan = span(0uL, 60uL),
                                blocks =
                                    listOf(
                                        MarkdownRenderBlock.ListBlock(
                                            sourceSpan = span(0uL, 60uL),
                                            ordered = false,
                                            startNumber = 1uL,
                                            items = listOf(nestedItem),
                                        ),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.totalBlocks shouldBe 1
            plan.items.size shouldBe 1
            val rootItem = plan.items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
            val quote = rootItem.block.shouldBeInstanceOf<MarkdownRenderBlock.BlockQuote>()
            val list = quote.blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.ListBlock>()
            val item = list.items.single()
            val paragraph = item.blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            val link = paragraph.inlines.filterIsInstance<MarkdownRenderInline.Link>().single()
            link.destination shouldBe "https://example.com"
        }

        test("given task item when plan is built then action span is available for interaction") {
            val taskItem =
                MarkdownRenderListItem(
                    sourceSpan = span(0uL, 24uL),
                    actionSpan = span(0uL, 3uL),
                    checked = false,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(4uL, 24uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(span(4uL, 24uL), "Complete the audit"),
                                    ),
                            ),
                        ),
                )
            val document =
                document(
                    sourceLength = 24uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.ListBlock(
                                sourceSpan = span(0uL, 24uL),
                                ordered = false,
                                startNumber = 1uL,
                                items = listOf(taskItem),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            val list = plan.items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block.shouldBeInstanceOf<MarkdownRenderBlock.ListBlock>()
            val item = list.items.single()
            item.actionSpan shouldBe span(0uL, 3uL)
            item.checked shouldBe false
        }

        test("given consecutive image only paragraphs when plan is built then images are grouped as gallery") {
            val document =
                document(
                    sourceLength = 80uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 30uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(0uL, 30uL),
                                            destination = "img1.png",
                                            title = null,
                                            altText = "First",
                                        ),
                                    ),
                            ),
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(31uL, 60uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(31uL, 60uL),
                                            destination = "img2.png",
                                            title = null,
                                            altText = "Second",
                                        ),
                                    ),
                            ),
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(61uL, 80uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(span(61uL, 80uL), "Trailing notes"),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.items.size shouldBe 2
            val gallery = plan.items.first().shouldBeInstanceOf<MarkdownIrPresentationItem.Gallery>()
            gallery.images.map { it.destination } shouldBe listOf("img1.png", "img2.png")
            gallery.images.map { it.altText } shouldBe listOf("First", "Second")
            val trailing = plan.items.last().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
            trailing.block.shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
        }

        test("given visible block limit when plan is built then presentation sequence is bounded without document mutation") {
            val document =
                document(
                    sourceLength = 120uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Heading(span(0uL, 20uL), 1u, listOf(MarkdownRenderInline.Text(span(2uL, 20uL), "Header"))),
                            MarkdownRenderBlock.Paragraph(span(21uL, 50uL), listOf(MarkdownRenderInline.Text(span(21uL, 50uL), "First paragraph"))),
                            MarkdownRenderBlock.Paragraph(span(51uL, 80uL), listOf(MarkdownRenderInline.Text(span(51uL, 80uL), "Second paragraph"))),
                            MarkdownRenderBlock.Paragraph(span(81uL, 120uL), listOf(MarkdownRenderInline.Text(span(81uL, 120uL), "Third paragraph"))),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document = document, maxVisibleBlocks = 2)

            plan.totalBlocks shouldBe 4
            plan.items.size shouldBe 2
            plan.items.map { it.shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block::class } shouldBe
                listOf(
                    MarkdownRenderBlock.Heading::class,
                    MarkdownRenderBlock.Paragraph::class,
                )
            document.blocks.size shouldBe 4
        }

        test("given empty document when plan is built then items are empty and count is zero") {
            val document = document(sourceLength = 0uL, blocks = emptyList())

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.totalBlocks shouldBe 0
            plan.items shouldBe emptyList()
        }

        test("given mixed content with table and break when plan is built then blocks match source layout") {
            val table =
                MarkdownRenderBlock.Table(
                    sourceSpan = span(0uL, 50uL),
                    header = listOf(MarkdownRenderTableCell(span(0uL, 10uL), listOf(MarkdownRenderInline.Text(span(0uL, 10uL), "Col1")))),
                    rows =
                        listOf(
                            listOf(MarkdownRenderTableCell(span(11uL, 20uL), listOf(MarkdownRenderInline.Text(span(11uL, 20uL), "Val1")))),
                        ),
                )
            val document =
                document(
                    sourceLength = 80uL,
                    blocks =
                        listOf(
                            table,
                            MarkdownRenderBlock.ThematicBreak(span(51uL, 54uL)),
                            MarkdownRenderBlock.CodeBlock(span(55uL, 80uL), "kotlin", "val x = 1"),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            plan.totalBlocks shouldBe 3
            plan.items.size shouldBe 3
            plan.items[0].shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block shouldBe table
            plan.items[1].shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block.shouldBeInstanceOf<MarkdownRenderBlock.ThematicBreak>()
            val code = plan.items[2].shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block.shouldBeInstanceOf<MarkdownRenderBlock.CodeBlock>()
            code.language shouldBe "kotlin"
            code.literal shouldBe "val x = 1"
        }

        test("given wiki reference in paragraph when plan is built then wiki node is retained for interaction") {
            val document =
                document(
                    sourceLength = 40uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 40uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.WikiReference(
                                            sourceSpan = span(0uL, 20uL),
                                            target = "TargetPage",
                                            inlines = listOf(MarkdownRenderInline.Text(span(0uL, 20uL), "My Target")),
                                        ),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            val paragraph = plan.items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block.shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            val wiki = paragraph.inlines.single().shouldBeInstanceOf<MarkdownRenderInline.WikiReference>()
            wiki.target shouldBe "TargetPage"
            val labelText = wiki.inlines.filterIsInstance<MarkdownRenderInline.Text>().joinToString("") { it.text }
            labelText shouldBe "My Target"
        }

        test("given wiki style embedded image when plan is built then image presentation node is emitted") {
            val document =
                document(
                    sourceLength = 30uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 30uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(0uL, 30uL),
                                            destination = "wiki-cover.png",
                                            title = null,
                                            altText = "",
                                        ),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            val item = plan.items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
            val paragraph = item.block.shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            val image = paragraph.inlines.single().shouldBeInstanceOf<MarkdownRenderInline.Image>()
            image.destination shouldBe "wiki-cover.png"
        }

        test("given nested wiki images inside blockquote when plan is built then image nodes are preserved") {
            val document =
                document(
                    sourceLength = 50uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.BlockQuote(
                                sourceSpan = span(0uL, 50uL),
                                blocks =
                                    listOf(
                                        MarkdownRenderBlock.Paragraph(
                                            sourceSpan = span(2uL, 48uL),
                                            inlines =
                                                listOf(
                                                    MarkdownRenderInline.Image(
                                                        sourceSpan = span(2uL, 48uL),
                                                        destination = "wiki-cover.png",
                                                        title = null,
                                                        altText = "",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document)

            val quote = plan.items.single().shouldBeInstanceOf<MarkdownIrPresentationItem.Block>().block.shouldBeInstanceOf<MarkdownRenderBlock.BlockQuote>()
            val paragraph = quote.blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            val imageNodes =
                paragraph.inlines.filterIsInstance<MarkdownRenderInline.Image>()
            imageNodes.map { it.destination } shouldBe listOf("wiki-cover.png")
        }

        test("given memo with inline tags and reminders when plan is built with MEMO_CARD policy then they are stripped and whitespace is normalized") {
            val document =
                document(
                    sourceLength = 60uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 60uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(span(0uL, 6uL), "Hello "),
                                        MarkdownRenderInline.Tag(span(6uL, 11uL), "test"),
                                        MarkdownRenderInline.Text(span(11uL, 20uL), "  world , "),
                                        MarkdownRenderInline.Reminder(span(20uL, 38uL), "@2026-09-02-12:00"),
                                        MarkdownRenderInline.Text(span(38uL, 43uL), " done"),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document = document, policy = MarkdownPresentationPolicy.MEMO_CARD)

            plan.totalBlocks shouldBe 1
            val paragraph =
                plan.items.single()
                    .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                    .block.shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            paragraph.inlines.filterIsInstance<MarkdownRenderInline.Tag>() shouldBe emptyList()
            paragraph.inlines.filterIsInstance<MarkdownRenderInline.Reminder>() shouldBe emptyList()
            val text = paragraph.inlines.filterIsInstance<MarkdownRenderInline.Text>().joinToString("") { it.text }
            text shouldBe "Hello world, done"
        }

        test("given memo with text followed by newline and tag when plan is built with MEMO_CARD policy then trailing line break is trimmed leaving no empty line") {
            val document =
                document(
                    sourceLength = 50uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 50uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(span(0uL, 20uL), "Quote text."),
                                        MarkdownRenderInline.SoftBreak(span(20uL, 21uL)),
                                        MarkdownRenderInline.Tag(span(21uL, 25uL), "tag"),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document = document, policy = MarkdownPresentationPolicy.MEMO_CARD)

            plan.totalBlocks shouldBe 1
            val paragraph =
                plan.items.single()
                    .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                    .block.shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            paragraph.inlines shouldBe
                listOf(
                    MarkdownRenderInline.Text(span(0uL, 20uL), "Quote text."),
                )
        }

        test("given pure tag and reminder memo when plan is built with MEMO_CARD policy then empty blocks are pruned leaving zero items") {
            val document =
                document(
                    sourceLength = 40uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 20uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Tag(span(0uL, 5uL), "tag1"),
                                        MarkdownRenderInline.Text(span(5uL, 6uL), " "),
                                        MarkdownRenderInline.Tag(span(6uL, 11uL), "tag2"),
                                    ),
                            ),
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(21uL, 40uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Reminder(span(21uL, 39uL), "@2026-09-02-12:00"),
                                    ),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document = document, policy = MarkdownPresentationPolicy.MEMO_CARD)

            plan.totalBlocks shouldBe 0
            plan.items shouldBe emptyList()
        }

        test("given task list item with tag when plan is built with MEMO_CARD policy then task interactive facts stay while tag is stripped") {
            val taskItem =
                MarkdownRenderListItem(
                    sourceSpan = span(0uL, 30uL),
                    actionSpan = span(2uL, 5uL),
                    checked = false,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(6uL, 30uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(span(6uL, 16uL), "Buy milk "),
                                        MarkdownRenderInline.Tag(span(16uL, 26uL), "groceries"),
                                    ),
                            ),
                        ),
                )
            val document =
                document(
                    sourceLength = 30uL,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.ListBlock(
                                sourceSpan = span(0uL, 30uL),
                                ordered = false,
                                startNumber = 1uL,
                                items = listOf(taskItem),
                            ),
                        ),
                )

            val plan = buildMarkdownIrPresentationPlan(document = document, policy = MarkdownPresentationPolicy.MEMO_CARD)

            plan.totalBlocks shouldBe 1
            val list =
                plan.items.single()
                    .shouldBeInstanceOf<MarkdownIrPresentationItem.Block>()
                    .block.shouldBeInstanceOf<MarkdownRenderBlock.ListBlock>()
            val item = list.items.single()
            item.actionSpan shouldBe span(2uL, 5uL)
            item.checked shouldBe false
            val innerParagraph = item.blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            innerParagraph.inlines.filterIsInstance<MarkdownRenderInline.Tag>() shouldBe emptyList()
            val text = innerParagraph.inlines.filterIsInstance<MarkdownRenderInline.Text>().joinToString("") { it.text }
            text shouldBe "Buy milk"
        }
    }
}

private fun document(
    sourceLength: ULong,
    blocks: List<MarkdownRenderBlock>,
): MarkdownRenderDocument =
    MarkdownRenderDocument(
        sourceByteLength = sourceLength,
        plainText = "ir",
        tagNames = emptyList(),
        attachmentDestinations = emptyList(),
        blocks = blocks,
    )

private fun span(
    start: ULong,
    end: ULong,
): MarkdownSourceSpan = MarkdownSourceSpan(startByte = start, endByte = end)
