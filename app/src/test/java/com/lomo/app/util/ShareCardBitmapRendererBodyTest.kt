package com.lomo.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: preprocessShareCardContent and buildShareBodyLines
 * - Behavior focus: share-card body preprocessing for image markers, audio preservation, body-line classification, blank-line collapsing, and max-line truncation.
 * - Observable outcomes: processed share-card text, image slot count, parsed ShareBodyLine types/text/imageIndex, and enforced line cap.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: bitmap pixel rendering, Android resources, and share intent/file-provider wiring.
 */
class ShareCardBitmapRendererBodyTest {
    @Test
    fun `preprocessShareCardContent replaces wiki and markdown images but preserves audio markdown`() {
        val content =
            """
            Intro
            ![[cover.png]]
            ![photo](gallery/day-1.jpg)
            ![voice](recordings/memo.m4a)
            Outro
            """.trimIndent()

        val result = preprocessShareCardContent(content, hasImages = true)

        assertTrue(result.hasImages)
        assertEquals(2, result.totalImageSlots)
        assertTrue(result.contentForProcessing.contains("${IMAGE_MARKER_PREFIX}0$IMAGE_MARKER_SUFFIX"))
        assertTrue(result.contentForProcessing.contains("${IMAGE_MARKER_PREFIX}1$IMAGE_MARKER_SUFFIX"))
        assertTrue(result.contentForProcessing.contains("![voice](recordings/memo.m4a)"))
        assertFalse(result.contentForProcessing.contains("cover.png"))
        assertFalse(result.contentForProcessing.contains("gallery/day-1.jpg"))
    }

    @Test
    fun `buildShareBodyLines classifies paragraphs quotes bullets code and image placeholders while collapsing blank lines`() {
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

        assertEquals(
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
            ),
            result,
        )
    }

    @Test
    fun `buildShareBodyLines falls back for blank content and caps rendered lines`() {
        val blankResult = buildShareBodyLines("", imagePlaceholder = "[Image]")
        val longResult =
            buildShareBodyLines(
                bodyText = List(MAX_SHARE_BODY_LINES + 10) { index -> "line $index" }.joinToString("\n"),
                imagePlaceholder = "[Image]",
            )

        assertEquals(listOf(ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Paragraph)), blankResult)
        assertEquals(MAX_SHARE_BODY_LINES, longResult.size)
        assertEquals("line 0", longResult.first().text)
        assertEquals("line ${MAX_SHARE_BODY_LINES - 1}", longResult.last().text)
    }
}
