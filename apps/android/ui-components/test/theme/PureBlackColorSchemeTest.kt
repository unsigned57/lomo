package com.lomo.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: opt-in OLED surfaces; owner: ui-components; priority: P1.
 * Scenarios:
 * Given a dark palette when pure black is enabled then background and base surfaces are black,
 * while accents and readable foreground colors are preserved. Light mode and disabled opt-in
 * retain their original palettes.
 * Production scope: apps/android/ui-components/src/theme/PureBlackColorScheme.kt
 * TDD proof: ui-components test run RED on background black assertion; same suite GREEN after fix.
 * Excludes: preference persistence and physical OLED energy measurements.
 * Observable outcomes: black base surfaces with preserved accents and foreground; unchanged disabled/light palettes.
 */
class PureBlackColorSchemeTest : UiComponentsFunSpec() {
    init {
        test("given dark mode when OLED is enabled then base surfaces are black and accents survive") {
            val palette = darkColorScheme()
            val actual = palette.withPureBlackSurfaces(isDark = true, enabled = true)
            actual.background shouldBe Color.Black
            actual.surface shouldBe Color.Black
            actual.surfaceDim shouldBe Color.Black
            actual.surfaceContainerLowest shouldBe Color.Black
            actual.primary shouldBe palette.primary
            actual.onSurface shouldBe palette.onSurface
        }
        test("given light mode or disabled OLED when resolving then the original palette is retained") {
            val light = lightColorScheme()
            light.withPureBlackSurfaces(isDark = false, enabled = true) shouldBe light
            val dark = darkColorScheme()
            dark.withPureBlackSurfaces(isDark = true, enabled = false) shouldBe dark
        }
    }
}
