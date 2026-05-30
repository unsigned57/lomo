package com.lomo.app.util

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 *
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: preprocessShareCardContent and buildShareBodyLines
 * - Behavior focus: share-card body preprocessing for image markers, audio preservation across supported voice formats, body-line classification, blank-line collapsing, and max-line truncation.
 * - Observable outcomes: processed share-card text, image slot count, parsed ShareBodyLine types/text/imageIndex, and enforced line cap.
 * - TDD proof: Fails before the fix because `.ogg` voice attachments are treated like images and replaced with image markers instead of being preserved as audio markdown.
 * - Excludes: bitmap pixel rendering, Android resources, and share intent/file-provider wiring.
 */
class ShareCardBitmapRendererBodyTest : AppFunSpec() {
    init {
        test("preprocessShareCardContent replaces wiki and markdown images but preserves audio markdown") {
            val content =
                """
                Intro
                ![[cover.png]]
                ![photo](gallery/day-1.jpg)
                ![voice](recordings/memo.ogg)
                Outro
                """.trimIndent()

            val result = preprocessShareCardContent(content, hasImages = true)

            ((result.hasImages)) shouldBe true
            (result.totalImageSlots) shouldBe (2)
            ((result.contentForProcessing.contains("${IMAGE_MARKER_PREFIX}0$IMAGE_MARKER_SUFFIX"))) shouldBe true
            ((result.contentForProcessing.contains("${IMAGE_MARKER_PREFIX}1$IMAGE_MARKER_SUFFIX"))) shouldBe true
            ((result.contentForProcessing.contains("![voice](recordings/memo.ogg)"))) shouldBe true
            ((result.contentForProcessing.contains("cover.png"))) shouldBe false
            ((result.contentForProcessing.contains("gallery/day-1.jpg"))) shouldBe false
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

            (result) shouldBe (listOf(
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
                ))
        }

        test("buildShareBodyLines falls back for blank content and caps rendered lines") {
            val blankResult = buildShareBodyLines("", imagePlaceholder = "[Image]")
            val longResult =
                buildShareBodyLines(
                    bodyText = List(MAX_SHARE_BODY_LINES + 10) { index -> "line $index" }.joinToString("\n"),
                    imagePlaceholder = "[Image]",
                )

            (blankResult) shouldBe (listOf(ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Paragraph)))
            (longResult.size) shouldBe (MAX_SHARE_BODY_LINES)
            (longResult.first().text) shouldBe ("line 0")
            (longResult.last().text) shouldBe ("line ${MAX_SHARE_BODY_LINES - 1}")
        }
    }
}
