package com.lomo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lomo.domain.model.ContrastLevel
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: preserve the chosen seed in accessible generated palettes; owner: ui-components; P1.
 * Scenarios:
 * - Given Klein blue, when generating standard light or dark palettes, then the primary container
 *   retains the selected color instead of replacing it with a muted blue.
 * - Given saturated and neutral seeds at every contrast tier, when generating either theme,
 *   then primary and primary-container text remains readable against its background.
 * Observable outcomes: generated ARGB colors and foreground/background contrast ratios.
 * TDD proof: RED `./kotlin test --include-module=ui-components
 * --include-classes='com.lomo.ui.theme.SeedColorFidelityTest'` generated a pale blue container
 * instead of #002FA7; rerun the same command for GREEN.
 * Excludes: display calibration and platform wallpaper extraction.
 */
class SeedColorFidelityTest : UiComponentsFunSpec() {
    init {
        test("given Klein blue when generating standard themes then primary container retains the selected color") {
            listOf(false, true).forEach { dark ->
                val colors = colorSchemeFromSeed(0xFF002FA7.toInt(), dark)
                colors.primaryContainer shouldBe Color(0xFF002FA7)
            }
        }

        test("given seed colors and contrast tiers when generating themes then primary text remains readable") {
            listOf(0xFF002FA7, 0xFFFF0000, 0xFF00FF00, 0xFF000000, 0xFFFFFFFF).forEach { seed ->
                listOf(false, true).forEach { dark ->
                    ContrastLevel.entries.forEach { contrast ->
                        val colors = colorSchemeFromSeed(seed.toInt(), dark, contrast)
                        contrastRatio(colors.onPrimary, colors.primary) shouldBeGreaterThanOrEqual 4.5
                        contrastRatio(colors.onPrimaryContainer, colors.primaryContainer) shouldBeGreaterThanOrEqual 4.5
                    }
                }
            }
        }
    }
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val a = foreground.luminance().toDouble()
    val b = background.luminance().toDouble()
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
