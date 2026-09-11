/*
 * Behavior Contract:
 * - Unit under test: MarkdownRenderDocument presentation-safe type law.
 * - Owning layer: domain DTO contract; Markdown semantics remain owned by lomo-workspace.
 * - Priority tier: P0.
 * - Capability: represent the Rust-owned nested render IR without transport fields or invalid spans.
 *
 * Scenarios:
 * - Given nested blocks and inlines with valid byte spans, when a document is constructed, then the
 *   hierarchy, typed link/image/task facts, and computed node count are preserved.
 * - Given a child span outside its parent or a task action span outside its list item, when a
 *   document is constructed, then the type boundary fails closed.
 *
 * Observable outcomes:
 * - Typed node fields, computed node count, and MarkdownRenderContractException code.
 *
 * TDD proof:
 * - RED before the fix: MarkdownRenderDocument, MarkdownRenderBlock, and MarkdownRenderInline do
 *   not exist in domain; the only Kotlin render DTO is data-internal and flat.
 *
 * Excludes:
 * - Markdown parsing, generated BoltFFI transport types, Compose layout, and production DI.
 */
package com.lomo.domain.model.markdown

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MarkdownRenderDocumentTest : DomainFunSpec() {
    init {
        test("given valid nested typed IR when document is constructed then hierarchy and facts are preserved") {
            val link =
                MarkdownRenderInline.Link(
                    sourceSpan = span(3uL, 15uL),
                    destination = "https://lomo.app",
                    title = "Lomo",
                    inlines =
                        listOf(
                            MarkdownRenderInline.Text(
                                sourceSpan = span(4uL, 8uL),
                                text = "Lomo",
                            ),
                        ),
                )
            val image =
                MarkdownRenderInline.Image(
                    sourceSpan = span(16uL, 28uL),
                    destination = "images/a.png",
                    title = null,
                    altText = "a",
                )
            val task =
                MarkdownRenderListItem(
                    sourceSpan = span(29uL, 48uL),
                    actionSpan = span(31uL, 34uL),
                    checked = false,
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(31uL, 48uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(
                                            sourceSpan = span(35uL, 48uL),
                                            text = "ship typed IR",
                                        ),
                                    ),
                            ),
                        ),
                )
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 48uL,
                    plainText = "Lomo a ship typed IR",
                    tagNames = listOf("p2"),
                    attachmentDestinations = listOf("images/a.png"),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 28uL),
                                inlines = listOf(link, image),
                            ),
                            MarkdownRenderBlock.ListBlock(
                                sourceSpan = span(29uL, 48uL),
                                ordered = false,
                                startNumber = 1uL,
                                items = listOf(task),
                            ),
                        ),
                )

            document.nodeCount shouldBe 8
            val paragraph = document.blocks.first().shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
            paragraph.inlines[0] shouldBe link
            paragraph.inlines[1] shouldBe image
            val list = document.blocks[1].shouldBeInstanceOf<MarkdownRenderBlock.ListBlock>()
            list.items.single().actionSpan shouldBe span(31uL, 34uL)
        }

        test("given a child or action span outside its owner when document is constructed then it fails closed") {
            shouldThrow<MarkdownRenderContractException> {
                MarkdownRenderDocument(
                    sourceByteLength = 8uL,
                    plainText = "outside",
                    tagNames = emptyList(),
                    attachmentDestinations = emptyList(),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0uL, 4uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(
                                            sourceSpan = span(3uL, 8uL),
                                            text = "outside",
                                        ),
                                    ),
                            ),
                        ),
                )
            }.code shouldBe "render_child_span_outside_parent"

            shouldThrow<MarkdownRenderContractException> {
                MarkdownRenderDocument(
                    sourceByteLength = 8uL,
                    plainText = "task",
                    tagNames = emptyList(),
                    attachmentDestinations = emptyList(),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.ListBlock(
                                sourceSpan = span(0uL, 8uL),
                                ordered = false,
                                startNumber = 1uL,
                                items =
                                    listOf(
                                        MarkdownRenderListItem(
                                            sourceSpan = span(0uL, 4uL),
                                            actionSpan = span(4uL, 6uL),
                                            checked = false,
                                            blocks = emptyList(),
                                        ),
                                    ),
                            ),
                        ),
                )
            }.code shouldBe "render_action_span_outside_node"
        }
    }
}

private fun span(start: ULong, end: ULong): MarkdownSourceSpan =
    MarkdownSourceSpan(startByte = start, endByte = end)
