package com.lomo.ui.theme

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Behavior Contract:
 * Capability: readable Expressive memo typography and seed-derived color roles; owner: ui-components; P1.
 * Scenarios: Given default or scaled reading preferences, when resolving memo styles, then reading,
 * editing and summaries share 14sp/21sp metrics with the user's multipliers applied.
 * Given saturated and neutral seeds, when resolving either theme, then text remains legible and
 * fixed accent roles derive from the selected seed instead of Material's unrelated default purple.
 * Observable outcomes: resolved text metrics, color contrast and fixed accent colors.
 * TDD proof: `./kotlin test --include-module=ui-components` observed RED against 15sp/22sp before
 * updating MemoTypography to 14sp/21sp; rerun for GREEN.
 * Test Change Justification:
 * Reason category: user-requested smaller default reading size.
 * Old behavior/assertion being replaced: 15sp/22sp defaults and 22.5sp/41.25sp scaled metrics.
 * Why old assertion is no longer correct: the user found 15sp too large; 14sp (M3 bodyMedium) keeps a 2sp step above
 *   12sp metadata, and the 1.5 line-height ratio (21sp) keeps CJK prose airy.
 * Coverage preserved by: baseline parity and independent font/line multipliers, plus color tests.
 * Why this is not fitting the test to the implementation: derives expected values directly from the 14sp/21sp base role and the existing user multipliers (1.5x font, 1.25x line height).
 * Excludes: OEM glyph rendering, screenshots and wallpaper extraction.
 */
class ExpressiveReadingContractTest : UiComponentsFunSpec() {
    init {
        test("given default reading preferences when resolving memo styles then all surfaces use 14sp by 21sp") {
            val typography = buildAppTypography(FontFamily.SansSerif)
            val scales = TypographyScales()
            listOf(
                typography.memoBodyTextStyle(scales),
                typography.memoEditorTextStyle(scales),
                typography.memoSummaryTextStyle(scales),
                typography.memoHintTextStyle(scales),
            ).forEach { style ->
                style.fontSize shouldBe 14.sp
                style.lineHeight shouldBe 21.sp
            }
        }

        test(
            "given customized reading preferences when resolving a memo then font and line multipliers remain effective",
        ) {
            val style = buildAppTypography(FontFamily.SansSerif).memoBodyTextStyle(
                TypographyScales(fontSizeScale = 1.5f, lineHeightScale = 1.25f),
            )
            style.fontSize shouldBe 21.sp
            style.lineHeight shouldBe 39.375.sp
        }

        test("given different seeds when resolving fixed accents then fixed roles belong to each seed") {
            val red = colorSchemeFromSeed(0xFFFF0000.toInt(), false)
            val green = colorSchemeFromSeed(0xFF00FF00.toInt(), false)
            red.primaryFixed shouldNotBe green.primaryFixed
            red.secondaryFixed shouldNotBe green.secondaryFixed
            red.tertiaryFixed shouldNotBe green.tertiaryFixed
        }

        test("given saturated or neutral seeds when resolving themes then normal text has accessible contrast") {
            val seeds = listOf(0xFFFFFF00, 0xFF00FF00, 0xFF0000FF, 0xFFFF0000, 0xFF000000, 0xFFFFFFFF)
            seeds.forEach { seed ->
                listOf(false, true).forEach { dark ->
                    val colors = colorSchemeFromSeed(seed.toInt(), dark)
                    listOf(
                        colors.onSurface to colors.surface,
                        colors.onPrimary to colors.primary,
                        colors.onSecondary to colors.secondary,
                        colors.onError to colors.error,
                    ).forEach { (foreground, background) ->
                        val a = foreground.luminance().toDouble()
                        val b = background.luminance().toDouble()
                        ((maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)) shouldBeGreaterThanOrEqual 4.5
                    }
                }
            }
        }
    }
}
