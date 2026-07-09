package com.lomo.ui.theme

import android.content.Context
import androidx.compose.material3.lightColorScheme
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.asOpaqueArgb
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk

/*
 * Behavior Contract:
 * - Unit under test: resolveLomoColorScheme (canonical ColorSource -> ColorScheme resolver)
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Capability: every surface (in-app theme and the off-composition share-card renderer) derives its scheme
 *   from the user's selected ColorSource through one resolver, instead of a divergent dynamic / Material-default path.
 *
 * Scenarios:
 * - Given a Preset ColorSource, when resolved, then the scheme equals colorSchemeFromSeed(presetSeed, isDark)
 *   for both light and dark.
 * - Given a CustomSeed ColorSource, when resolved, then the scheme equals colorSchemeFromSeed(customArgb, isDark).
 * - Given two different presets, when resolved, then their accent (primary) differs and is not the Material 3
 *   baseline default - i.e. the palette actually follows the selected source (the share-card defect).
 *
 * Observable outcomes:
 * - The returned ColorScheme primary/surface/onPrimaryContainer slots versus colorSchemeFromSeed and versus the
 *   Material baseline lightColorScheme().
 *
 * TDD proof:
 * - RED: resolveLomoColorScheme did not exist; the share card built its own scheme (system dynamic colors or the
 *   Material baseline), so a Preset's resolved primary did not match colorSchemeFromSeed(presetSeed).
 * - RED command: `./kotlin test --include-classes='com.lomo.ui.theme.LomoColorSchemeTest'`.
 * - GREEN: the resolver routes Preset / CustomSeed through colorSchemeFromSeed.
 *
 * Excludes:
 * - The DynamicWallpaper path (platform wallpaper extraction needs a real Android context) and Compose runtime.
 */
class LomoColorSchemeTest : UiComponentsFunSpec() {
    // Context is only touched on the DynamicWallpaper path, which is out of scope here; the Preset / CustomSeed
    // paths never call it, so an unused framework mock is the right seam.
    private val context = mockk<Context>()

    init {
        test("preset color source resolves through the seed palette pipeline for light and dark") {
            listOf(false, true).forEach { isDark ->
                ColorPresetId.entries.forEach { preset ->
                    val resolved = resolveLomoColorScheme(context, ColorSource.Preset(preset), isDark)
                    val expected = colorSchemeFromSeed(preset.seedArgb, isDark)

                    resolved.primary shouldBe expected.primary
                    resolved.surface shouldBe expected.surface
                    resolved.onPrimaryContainer shouldBe expected.onPrimaryContainer
                }
            }
        }

        test("custom seed color source resolves through the seed palette pipeline") {
            val argb = asOpaqueArgb(0x336699)

            val resolved = resolveLomoColorScheme(context, ColorSource.CustomSeed(argb), isDark = false)
            val expected = colorSchemeFromSeed(argb, isDark = false)

            resolved.primary shouldBe expected.primary
            resolved.surface shouldBe expected.surface
        }

        test("resolved palette follows the selected source and is not the Material baseline default") {
            val forest = resolveLomoColorScheme(context, ColorSource.Preset(ColorPresetId.FOREST), isDark = false)
            val ocean = resolveLomoColorScheme(context, ColorSource.Preset(ColorPresetId.OCEAN), isDark = false)

            forest.primary shouldNotBe ocean.primary
            ocean.primary shouldNotBe lightColorScheme().primary
        }
    }
}
