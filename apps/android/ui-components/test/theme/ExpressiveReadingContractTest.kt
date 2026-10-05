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
 * editing and summaries share 16sp/24sp metrics with the user's multipliers applied.
 * Given saturated and neutral seeds, when resolving either theme, then text remains legible and
 * fixed accent roles derive from the selected seed instead of Material's unrelated default purple.
 * Observable outcomes: resolved text metrics, color contrast and fixed accent colors.
 * TDD proof: run this spec before implementing the Expressive theme; record RED/GREEN in the handoff.
 * Excludes: OEM glyph rendering, screenshots and wallpaper extraction.
 */
class ExpressiveReadingContractTest : UiComponentsFunSpec() {
    init {
        test("given default reading preferences when resolving memo styles then all surfaces use 16sp by 24sp") {
            val typography = buildAppTypography(FontFamily.SansSerif)
            val scales = TypographyScales()
            listOf(
                typography.memoBodyTextStyle(scales),
                typography.memoEditorTextStyle(scales),
                typography.memoSummaryTextStyle(scales),
                typography.memoHintTextStyle(scales),
            ).forEach { style ->
                style.fontSize shouldBe 16.sp
                style.lineHeight shouldBe 24.sp
            }
        }

        test("given customized reading preferences when resolving a memo then font and line multipliers remain effective") {
            val style = buildAppTypography(FontFamily.SansSerif).memoBodyTextStyle(
                TypographyScales(fontSizeScale = 1.5f, lineHeightScale = 1.25f),
            )
            style.fontSize shouldBe 24.sp
            style.lineHeight shouldBe 45.sp
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
