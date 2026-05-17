package com.lomo.ui.theme

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle

/*
 * Test Contract:
 * - Unit under test: memo typography visual-parity tokens.
 * - Behavior focus: editor-side Compose text tokens must opt out of platform font padding so the input panel matches the tighter TextView-backed memo card rhythm.
 * - Observable outcomes: resolved platform text style for memo body, editor, summary, and hint tokens.
 * - Red phase: Fails before the fix because memo typography leaves Compose-side platform font padding at the default, making input-panel text metrics drift from memo card rendering.
 * - Excludes: actual TextField layout, OEM font metrics, and card/editor container padding.
 */
class MemoTypographyVisualParityTest : UiComponentsFunSpec() {
    private val typography = Typography()
    private val defaultScales = TypographyScales()

    init {
        test("memo typography tokens disable platform font padding for card and editor parity") {
        val expectedPlatformStyle = PlatformTextStyle(includeFontPadding = false)

        (typography.memoBodyTextStyle(defaultScales).platformStyle) shouldBe (expectedPlatformStyle)
        (typography.memoBodyTextStyle(defaultScales).platformStyle) shouldBe (expectedPlatformStyle) // editor delegates to body
        (typography.memoSummaryTextStyle(defaultScales).platformStyle) shouldBe (expectedPlatformStyle)
        (typography.memoHintTextStyle(defaultScales).platformStyle) shouldBe (expectedPlatformStyle)
        }
    }
}
