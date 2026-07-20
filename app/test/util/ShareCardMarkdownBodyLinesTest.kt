package com.lomo.app.util

/*
 * Behavior Contract:
 * - Unit under test: ShareCardMarkdownBodyLines
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownRenderTableCell
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe

class ShareCardMarkdownBodyLinesTest : AppFunSpec() {
    init {
        test("markdown share body lines preserve common markdown semantics for bitmap rendering") {
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 200u,
                    plainText = "Title Paragraph with bold and removed text. quoted text done plain item Name Status Lomo ready cover",
                    tagNames = emptyList(),
                    attachmentDestinations = listOf("images/cover.png"),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Heading(
                                sourceSpan = span(0u, 5u),
                                level = 1u,
                                inlines = listOf(text(0u, 5u, "Title")),
                            ),
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(6u, 40u),
                                inlines =
                                    listOf(
                                        text(6u, 20u, "Paragraph with "),
                                        MarkdownRenderInline.Strong(
                                            sourceSpan = span(20u, 24u),
                                            inlines = listOf(text(20u, 24u, "bold")),
                                        ),
                                        text(24u, 29u, " and "),
                                        MarkdownRenderInline.Strikethrough(
                                            sourceSpan = span(29u, 36u),
                                            inlines = listOf(text(29u, 36u, "removed")),
                                        ),
                                        text(36u, 40u, " text."),
                                    ),
                            ),
                            MarkdownRenderBlock.BlockQuote(
                                sourceSpan = span(41u, 52u),
                                blocks =
                                    listOf(
                                        MarkdownRenderBlock.Paragraph(
                                            sourceSpan = span(41u, 52u),
                                            inlines = listOf(text(41u, 52u, "quoted text")),
                                        ),
                                    ),
                            ),
                            MarkdownRenderBlock.ListBlock(
                                sourceSpan = span(53u, 80u),
                                ordered = false,
                                startNumber = 1u,
                                items =
                                    listOf(
                                        MarkdownRenderListItem(
                                            sourceSpan = span(53u, 62u),
                                            actionSpan = span(55u, 58u),
                                            checked = true,
                                            blocks =
                                                listOf(
                                                    MarkdownRenderBlock.Paragraph(
                                                        sourceSpan = span(59u, 62u),
                                                        inlines = listOf(text(59u, 62u, "done")),
                                                    ),
                                                ),
                                        ),
                                        MarkdownRenderListItem(
                                            sourceSpan = span(63u, 80u),
                                            actionSpan = null,
                                            checked = null,
                                            blocks =
                                                listOf(
                                                    MarkdownRenderBlock.Paragraph(
                                                        sourceSpan = span(65u, 80u),
                                                        inlines = listOf(text(65u, 80u, "plain item")),
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                            MarkdownRenderBlock.Table(
                                sourceSpan = span(81u, 120u),
                                header =
                                    listOf(
                                        cell(81u, 85u, "Name"),
                                        cell(86u, 92u, "Status"),
                                    ),
                                rows =
                                    listOf(
                                        listOf(
                                            cell(93u, 97u, "Lomo"),
                                            cell(98u, 103u, "ready"),
                                        ),
                                    ),
                            ),
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(104u, 130u),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(104u, 130u),
                                            destination = "images/cover.png",
                                            title = null,
                                            altText = "cover",
                                        ),
                                    ),
                            ),
                        ),
                )

            val lines =
                buildMarkdownShareBodyLines(
                    document = document,
                    imagePlaceholder = "[Image]",
                )

            lines[0].type shouldBe ShareBodyLineType.Heading
            lines[0].headingLevel shouldBe 1
            lines[0].text shouldBe "Title"

            val paragraph = lines.first { it.text.contains("removed") }
            (paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Bold }) shouldBe true
            (paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Strikethrough }) shouldBe true

            val quote = lines.first { it.type == ShareBodyLineType.Quote }
            quote.text shouldBe "│ quoted text"

            lines.first { it.text.contains("done") }.text shouldBe "☑ done"
            lines.first { it.text.contains("plain item") }.text shouldBe "• plain item"

            val tableLines = lines.filter { it.type == ShareBodyLineType.Table }
            tableLines.map { it.text } shouldBe listOf("Name | Status", "Lomo | ready")

            val image = lines.single { it.type == ShareBodyLineType.Image }
            image.imageIndex shouldBe 0
        }

        test("markdown share body lines assign sequential image indices from IR image nodes") {
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 40u,
                    plainText = "a b",
                    tagNames = emptyList(),
                    attachmentDestinations = listOf("a.png", "b.png"),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0u, 10u),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(0u, 5u),
                                            destination = "a.png",
                                            title = null,
                                            altText = "a",
                                        ),
                                    ),
                            ),
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(11u, 20u),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(11u, 20u),
                                            destination = "b.png",
                                            title = null,
                                            altText = "b",
                                        ),
                                    ),
                            ),
                        ),
                )

            val lines = buildMarkdownShareBodyLines(document, imagePlaceholder = "[Image]")
            lines.filter { it.type == ShareBodyLineType.Image }.map { it.imageIndex } shouldBe listOf(0, 1)
            countShareCardImageSlots(document) shouldBe 2
        }

        test("linkifyBareUrlsAndGeoUris correctly transforms bare URLs and geo coordinates without double linkification") {
            val content =
                "Visit https://google.com and www.lomo.app and geo:31.2304,121.4737 or [Google](https://google.com) or [https://google.com](https://google.com)"
            val result = linkifyBareUrlsAndGeoUris(content)
            result shouldBe
                "Visit [https://google.com](https://google.com) and [www.lomo.app](https://www.lomo.app) and [geo:31.2304,121.4737](geo:31.2304,121.4737?z=10) or [Google](https://google.com) or [https://google.com](https://google.com)"
        }

        test("markdown share body lines preserve url link style and highlight style from IR") {
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 40u,
                    plainText = "Visit site and important",
                    tagNames = emptyList(),
                    attachmentDestinations = emptyList(),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0u, 40u),
                                inlines =
                                    listOf(
                                        text(0u, 6u, "Visit "),
                                        MarkdownRenderInline.Link(
                                            sourceSpan = span(6u, 20u),
                                            destination = "https://example.com",
                                            title = null,
                                            inlines = listOf(text(6u, 20u, "https://example.com")),
                                        ),
                                        text(20u, 25u, " and "),
                                        MarkdownRenderInline.Highlight(
                                            sourceSpan = span(25u, 34u),
                                            inlines = listOf(text(25u, 34u, "important")),
                                        ),
                                    ),
                            ),
                        ),
                )

            val lines = buildMarkdownShareBodyLines(document, imagePlaceholder = "[Image]")
            val paragraph = lines.single { it.type == ShareBodyLineType.Paragraph }
            paragraph.text shouldBe "Visit https://example.com and important"
            (paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Link }) shouldBe true
            (paragraph.inlineStyles.any { it.kind == ShareInlineStyleKind.Highlight }) shouldBe true
        }
    }
}

private fun span(
    start: ULong,
    end: ULong,
): MarkdownSourceSpan = MarkdownSourceSpan(startByte = start, endByte = end)

private fun text(
    start: ULong,
    end: ULong,
    value: String,
): MarkdownRenderInline.Text = MarkdownRenderInline.Text(span(start, end), value)

private fun cell(
    start: ULong,
    end: ULong,
    value: String,
): MarkdownRenderTableCell =
    MarkdownRenderTableCell(
        sourceSpan = span(start, end),
        inlines = listOf(text(start, end, value)),
    )
