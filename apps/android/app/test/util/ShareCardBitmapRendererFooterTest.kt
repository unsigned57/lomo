package com.lomo.app.util

/*
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
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Behavior Contract:
 * - Unit under test: ShareCardModels footer, timestamp, and centered-body layout policy helpers.
 * - Behavior focus: single-line footer composition, file-native timestamp preference with fallback
 *   formatting, and short-paragraph centering rules.
 * - Observable outcomes: ShareCardFooterContent rows, resolved timestamp string, and centered-body bit.
 * - TDD proof: Fails when created-at text ignores file-native strings or heading/code/image memos center-align.
 * - Test Change Justification: reason category = product contract changed; ShareCard moved from Canvas
 *   renderer to Compose MarkdownRenderer, adding resolveShareCardCreatedAtText and shouldUseCenteredShareCardBody
 *   alongside buildShareCardFooterContent (also consolidating separate init blocks per NoPerTestInitBlock).
 * - Excludes: Compose layout frames, Android Canvas rasterization, and Context resource lookup.
 */
class ShareCardBitmapRendererFooterTest : AppFunSpec() {
    init {
        test("buildShareCardFooterContent keeps time and signature on a single footer row when both are enabled") {
            val result =
                buildShareCardFooterContent(
                    showTime = true,
                    showSignature = true,
                    signatureText = "Unsigned57",
                    createdAtText = "2026-04-15 09:30",
                )

            result shouldBe
                ShareCardFooterContent(
                    showFooter = true,
                    row =
                    ShareCardFooterRow(
                        startText = "2026-04-15 09:30",
                        endText = "Unsigned57",
                    ),
                )
        }

        test("buildShareCardFooterContent falls back blank signature to Lomo and keeps signature on bottom row") {
            val result =
                buildShareCardFooterContent(
                    showTime = false,
                    showSignature = true,
                    signatureText = "   ",
                    createdAtText = "2026-04-15 09:30",
                )

            result shouldBe
                ShareCardFooterContent(
                    showFooter = true,
                    row =
                    ShareCardFooterRow(
                        centerText = "Lomo",
                    ),
                )
        }

        test("buildShareCardFooterContent hides footer when all footer values are disabled or blank") {
            val result =
                buildShareCardFooterContent(
                    showTime = false,
                    showSignature = false,
                    signatureText = "Unsigned57",
                    createdAtText = "2026-04-15 09:30",
                )

            result shouldBe
                ShareCardFooterContent(
                    showFooter = false,
                    row = null,
                )
        }

        test("resolveShareCardCreatedAtText prefers trimmed file-native text over fallback timestamp") {
            val formatter = DateTimeFormatter.ofPattern(SHARE_CARD_TIME_PATTERN)

            resolveShareCardCreatedAtText(
                createdAtText = "  2026_05_25 09:30:00  ",
                timestampMillis = 0L,
                formatter = formatter,
            ) shouldBe "2026_05_25 09:30:00"

            val sampleEpoch = 1_779_694_200_000L
            val expectedFallback = Instant
                .ofEpochMilli(sampleEpoch)
                .atZone(ZoneId.systemDefault())
                .format(formatter)
            resolveShareCardCreatedAtText(
                createdAtText = "   ",
                timestampMillis = sampleEpoch,
                formatter = formatter,
            ) shouldBe expectedFallback
        }

        test("shouldUseCenteredShareCardBody centers only short single-paragraph text without images") {
            val shortText = "Short thought"
            shouldUseCenteredShareCardBody(
                document = paragraphDoc(shortText),
                bodyText = shortText,
                hasImages = false,
            ) shouldBe true

            shouldUseCenteredShareCardBody(
                document = paragraphDoc(shortText),
                bodyText = shortText,
                hasImages = true,
            ) shouldBe false

            val longText = "a".repeat(SHORT_BODY_CENTER_THRESHOLD + 1)
            shouldUseCenteredShareCardBody(
                document = paragraphDoc(longText),
                bodyText = longText,
                hasImages = false,
            ) shouldBe false

            val fourLines = "1\n2\n3\n4"
            shouldUseCenteredShareCardBody(
                document = paragraphDoc(fourLines),
                bodyText = fourLines,
                hasImages = false,
            ) shouldBe false

            val headingSpan = MarkdownSourceSpan(0uL, shortText.encodeToByteArray().size.toULong())
            val headingDoc =
                MarkdownRenderDocument(
                    sourceByteLength = headingSpan.endByte,
                    plainText = shortText,
                    tagNames = emptyList(),
                    attachments = emptyList(),
                    blocks =
                    listOf(
                        MarkdownRenderBlock.Heading(
                            sourceSpan = headingSpan,
                            level = 1u,
                            inlines = listOf(MarkdownRenderInline.Text(headingSpan, shortText)),
                        ),
                    ),
                )
            shouldUseCenteredShareCardBody(
                document = headingDoc,
                bodyText = shortText,
                hasImages = false,
            ) shouldBe false
        }
    }
}

private fun paragraphDoc(text: String): MarkdownRenderDocument {
    val span = MarkdownSourceSpan(0uL, text.encodeToByteArray().size.toULong())
    return MarkdownRenderDocument(
        sourceByteLength = span.endByte,
        plainText = text,
        tagNames = emptyList(),
        attachments = emptyList(),
        blocks =
        listOf(
            MarkdownRenderBlock.Paragraph(
                sourceSpan = span,
                inlines = listOf(MarkdownRenderInline.Text(span, text)),
            ),
        ),
    )
}
