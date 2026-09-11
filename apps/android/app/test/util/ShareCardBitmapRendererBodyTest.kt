/*
 * Behavior Contract:
 * - Unit under test: ShareCardBitmapRendererBody
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

package com.lomo.app.util

/**
 * Behavior Contract:
 * - Unit under test: countShareCardImageSlots + buildShareBodyLines
 * - Behavior focus: image slots come from owner IR image/attachment nodes (not Markdown regex);
 *   presentation line classification for already-tokenized share lines.
 * - Observable outcomes: image slot counts, ShareBodyLine types/text/imageIndex, line cap.
 * - TDD proof: fails if production reintroduces WIKI_IMAGE_REGEX/MD_IMAGE_REGEX image parse.
 * - Excludes: bitmap pixel rendering, Android resources, share intent wiring.
 *
 * Test Change Justification:
 * - Reason category: Stage-2 cutover — share-card image identity moves to Rust IR.
 * - Old behavior: preprocessShareCardContent replaced wiki/MD images via production regex.
 * - Why old assertion is wrong: Kotlin must not re-identify attachments after P2-09 switch.
 * - Coverage preserved: image vs audio slot count and line classification scenarios retained.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe

class ShareCardBitmapRendererBodyTest : AppFunSpec() {
    init {
        test("countShareCardImageSlots counts IR images and skips audio destinations") {
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 64u,
                    plainText = "Intro cover photo voice Outro",
                    tagNames = emptyList(),
                    attachmentDestinations = listOf("cover.png", "gallery/day-1.jpg", "recordings/memo.ogg"),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = span(0u, 10u),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(0u, 4u),
                                            destination = "cover.png",
                                            title = null,
                                            altText = "cover",
                                        ),
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(4u, 8u),
                                            destination = "gallery/day-1.jpg",
                                            title = null,
                                            altText = "photo",
                                        ),
                                        MarkdownRenderInline.Image(
                                            sourceSpan = span(8u, 10u),
                                            destination = "recordings/memo.ogg",
                                            title = null,
                                            altText = "voice",
                                        ),
                                    ),
                            ),
                        ),
                )

            countShareCardImageSlots(document) shouldBe 2
        }

        test("buildShareBodyLines classifies paragraphs quotes bullets code and image placeholders while collapsing blank lines") {
            val bodyText =
                """
                Intro ${IMAGE_MARKER_PREFIX}0$IMAGE_MARKER_SUFFIX outro

                │ quoted text
                • bullet item
                ☐ task item
                    val x = 1
                ${IMAGE_MARKER_PREFIX}1$IMAGE_MARKER_SUFFIX

                Final paragraph
                """.trimIndent()

            val result = buildShareBodyLines(bodyText, imagePlaceholder = "[Image]")

            result shouldBe
                listOf(
                    ShareBodyLine("Intro [Image] outro", ShareBodyLineType.Paragraph),
                    ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Blank),
                    ShareBodyLine("quoted text", ShareBodyLineType.Quote),
                    ShareBodyLine("• bullet item", ShareBodyLineType.Bullet),
                    ShareBodyLine("☐ task item", ShareBodyLineType.Bullet),
                    ShareBodyLine("val x = 1", ShareBodyLineType.Code),
                    ShareBodyLine(
                        "${IMAGE_MARKER_PREFIX}1$IMAGE_MARKER_SUFFIX",
                        ShareBodyLineType.Image,
                        imageIndex = 1,
                    ),
                    ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Blank),
                    ShareBodyLine("Final paragraph", ShareBodyLineType.Paragraph),
                )
        }

        test("buildShareBodyLines falls back for blank content and caps rendered lines") {
            val blankResult = buildShareBodyLines("", imagePlaceholder = "[Image]")
            val longResult =
                buildShareBodyLines(
                    bodyText = List(MAX_SHARE_BODY_LINES + 10) { index -> "line $index" }.joinToString("\n"),
                    imagePlaceholder = "[Image]",
                )

            blankResult shouldBe listOf(ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Paragraph))
            longResult.size shouldBe MAX_SHARE_BODY_LINES
            longResult.first().text shouldBe "line 0"
            longResult.last().text shouldBe "line ${MAX_SHARE_BODY_LINES - 1}"
        }
    }
}

private fun span(
    start: ULong,
    end: ULong,
): MarkdownSourceSpan = MarkdownSourceSpan(startByte = start, endByte = end)
