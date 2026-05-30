package com.lomo.ui.theme

import androidx.compose.ui.graphics.Color
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.asOpaqueArgb
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: colorSchemeFromSeed + ColorSource.resolvePresetSeedArgb (pure functions).
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Capability: derive a full Material 3 ColorScheme from any ARGB seed using the same HCT algorithm
 *   the platform uses for Material You. Replaces the legacy hardcoded LightColorScheme/DarkColorScheme.
 *
 * Scenarios:
 * - Given the same seed and dark flag, when invoked twice, then it returns the same primary/onPrimary
 *   (purity / determinism).
 * - Given the same seed but different dark flags, when invoked, then the primary swaps to a darker/lighter
 *   tone (light vs dark schemes differ in lightness).
 * - Given any preset seed, when used to derive a scheme, then primary is opaque (alpha = 0xFF) — a
 *   subtle but common bug class when porting from older Material color libraries.
 * - Given DynamicWallpaper, when resolvePresetSeedArgb is called, then it falls back to the Indigo seed
 *   (matches the on-Android < S fallback path in Theme.kt).
 *
 * Observable outcomes:
 * - ColorScheme equality of primary, surface, and other key slots across calls.
 * - Alpha channel of all derived colours is fully opaque.
 *
 * TDD proof:
 * - Fails before the fix because colorSchemeFromSeed / ColorSource don't exist; static seed-derived
 *   colours regress to the old hand-picked Indigo brand palette.
 *
 * Excludes:
 * - Compose runtime state, animations, status bar updates.
 */
class ColorSchemeGeneratorTest : UiComponentsFunSpec() {
    init {
        test("same seed and theme produce identical schemes") {
            val seed = ColorPresetId.INDIGO.seedArgb
            val a = colorSchemeFromSeed(seed, isDark = false)
            val b = colorSchemeFromSeed(seed, isDark = false)
            a.primary shouldBe b.primary
            a.surface shouldBe b.surface
            a.onPrimaryContainer shouldBe b.onPrimaryContainer
        }

        test("light and dark seed-derived schemes differ in surface and primary") {
            val seed = ColorPresetId.OCEAN.seedArgb
            val light = colorSchemeFromSeed(seed, isDark = false)
            val dark = colorSchemeFromSeed(seed, isDark = true)
            light.primary shouldNotBe dark.primary
            light.surface shouldNotBe dark.surface
        }

        test("derived colours are fully opaque") {
            ColorPresetId.entries.forEach { preset ->
                val light = colorSchemeFromSeed(preset.seedArgb, isDark = false)
                val dark = colorSchemeFromSeed(preset.seedArgb, isDark = true)
                listOf(light.primary, light.surface, light.onPrimary, dark.primary, dark.surface).forEach { color ->
                    color.opaqueAlpha() shouldBe Color.Black.opaqueAlpha()
                }
            }
        }

        test("DynamicWallpaper falls back to Indigo seed when no platform extraction is available") {
            ColorSource.DynamicWallpaper.resolvePresetSeedArgb() shouldBe ColorPresetId.INDIGO.seedArgb
        }

        test("Preset and CustomSeed resolve to their declared ARGB values") {
            ColorSource.Preset(ColorPresetId.FOREST).resolvePresetSeedArgb() shouldBe ColorPresetId.FOREST.seedArgb
            ColorSource.CustomSeed(asOpaqueArgb(0x123456)).resolvePresetSeedArgb() shouldBe 0xFF123456.toInt()
        }
    }
}

private fun Color.opaqueAlpha(): Float = alpha
