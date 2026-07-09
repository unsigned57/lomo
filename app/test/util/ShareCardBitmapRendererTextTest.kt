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
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: shouldUseCenteredBody, bodyTextSizeSp, and quote line layout policy.
 * - Behavior focus: centered-body layout policy, body text size thresholds, and share-card quote block visual affordance.
 * - Observable outcomes: Boolean decision to use centered body, selected body text size, and quote indicator/text layout metrics.
 * - TDD proof: Quote layout test fails before the fix because share-card quote lines are rendered as plain text without a rounded indicator bar or text inset.
 * - Test Change Justification: reason category = pure refactor preserved behavior; removed the obsolete activeDayCountText constructor argument after recorded-days footer content was deleted. Coverage is preserved by keeping the same centered-body and text-size assertions. This is not fitting the test to the implementation because the assertions remain identical and only the input shape changed.
 * - Excludes: bitmap drawing, text paint construction, and Android typography/resource lookups.
 */
class ShareCardBitmapRendererTextTest : AppFunSpec() {
    init {
        test("shouldUseCenteredBody returns true for short headerless paragraph only body") {
            val input =
                ShareCardRenderInput(
                    displayTags = emptyList(),
                    title = null,
                    safeText = "Short reflective note",
                    imagePlaceholder = "[Image]",
                    createdAtText = "2026-03-25 12:00",
                    textLengthWithoutMarkers = 21,
                    hasImages = false,
                )
            val bodyLines =
                listOf(
                    ShareBodyLine("Short reflective note", ShareBodyLineType.Paragraph),
                    ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Blank),
                    ShareBodyLine("Second line", ShareBodyLineType.Paragraph),
                )

            ((shouldUseCenteredBody(input, bodyLines))) shouldBe true
        }
    }

    init {
        test("shouldUseCenteredBody rejects headers images long bodies and non paragraph lines") {
            val baseInput =
                ShareCardRenderInput(
                    displayTags = emptyList(),
                    title = null,
                    safeText = "Centered candidate",
                    imagePlaceholder = "[Image]",
                    createdAtText = "2026-03-25 12:00",
                    textLengthWithoutMarkers = 18,
                    hasImages = false,
                )
            val paragraphLines = listOf(ShareBodyLine("Centered candidate", ShareBodyLineType.Paragraph))

            ((shouldUseCenteredBody(
                    baseInput.copy(displayTags = listOf("tag")),
                    paragraphLines,
                ))) shouldBe false
            ((shouldUseCenteredBody(
                    baseInput.copy(title = "Title"),
                    paragraphLines,
                ))) shouldBe false
            ((shouldUseCenteredBody(
                    baseInput.copy(hasImages = true),
                    paragraphLines,
                ))) shouldBe false
            ((shouldUseCenteredBody(
                    baseInput.copy(textLengthWithoutMarkers = SHORT_BODY_CENTER_THRESHOLD + 1),
                    paragraphLines,
                ))) shouldBe false
            ((shouldUseCenteredBody(
                    baseInput,
                    listOf(ShareBodyLine("• bullet", ShareBodyLineType.Bullet)),
                ))) shouldBe false
            ((shouldUseCenteredBody(
                    baseInput,
                    List(SHORT_BODY_MAX_NON_BLANK_LINES + 1) { index ->
                        ShareBodyLine("line $index", ShareBodyLineType.Paragraph)
                    },
                ))) shouldBe false
        }
    }

    init {
        test("bodyTextSizeSp scales down only at configured thresholds") {
            (bodyTextSizeSp(BODY_TEXT_SIZE_SHORT_THRESHOLD)) shouldBe ((BODY_TEXT_SIZE_SHORT_SP) plusOrMinus 0.0f)
            (bodyTextSizeSp(BODY_TEXT_SIZE_SHORT_THRESHOLD + 1)) shouldBe ((BODY_TEXT_SIZE_MEDIUM_SP) plusOrMinus 0.0f)
            (bodyTextSizeSp(BODY_TEXT_SIZE_MEDIUM_THRESHOLD + 1)) shouldBe ((BODY_TEXT_SIZE_LONG_SP) plusOrMinus 0.0f)
            (bodyTextSizeSp(BODY_TEXT_SIZE_LONG_THRESHOLD + 1)) shouldBe ((BODY_TEXT_SIZE_DEFAULT_SP) plusOrMinus 0.0f)
        }
    }

    init {
        test("quote layout reserves rounded indicator bar and text inset") {
            val spec = shareCardLayoutSpecForTest(contentWidth = 320)

            val style = resolveShareCardQuoteLayoutStyle(spec)

            (style.indicatorWidth) shouldBe ((4f) plusOrMinus 0.0f)
            (style.indicatorCornerRadius) shouldBe ((2f) plusOrMinus 0.0f)
            (style.textStartOffset) shouldBe ((12f) plusOrMinus 0.0f)
            (style.textWidth) shouldBe (308)
        }
    }

}

private fun shareCardLayoutSpecForTest(contentWidth: Int): ShareCardLayoutSpec =
    ShareCardLayoutSpec(
        canvasWidth = 400,
        outerPadding = 0f,
        cardPadding = 0f,
        cardCorner = 0f,
        lineSpacing = 0f,
        tagBottomSpacing = 0f,
        titleBottomSpacing = 0f,
        codeHorizontalPadding = 0f,
        codeVerticalPadding = 0f,
        codeCorner = 0f,
        imageCorner = 0f,
        imageVerticalPadding = 0f,
        maxImageHeightPx = 0f,
        dividerTopSpacing = 0f,
        dividerStrokeWidth = 0f,
        footerRowTopSpacing = 0f,
        minCardHeight = 0f,
        contentWidth = contentWidth,
        quoteIndicatorWidth = 4f,
        quoteIndicatorCornerRadius = 2f,
        quoteTextStartPadding = 8f,
    )
