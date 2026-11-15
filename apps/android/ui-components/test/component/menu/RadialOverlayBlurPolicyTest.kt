package com.lomo.ui.component.menu

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: resolveRadialOverlayBlurSpec for RadialSubActionOverlay Haze backdrop scrim.
 * - Owning layer: ui-components presentation.
 * - Priority tier: P1.
 * - Capability: normalize radial scrim transition progress and resolve full-backdrop blur radius
 *   and tint colors without collapsing blur before entry or exceeding scrim alpha bounds.
 *
 * Scenarios:
 * - Given out-of-range effectsAlpha values (< 0f or > 1f), when resolved, then alpha is clamped
 *   to [0f, 1f] while blur radius matches ActionSheetTokens.RadialBlurRadius.
 * - Given full progress (1f) and a ColorScheme, when resolved, then tintColor carries
 *   ActionSheetTokens.RadialScrimAlpha on colorScheme.scrim and fallbackTintColor scales by alpha.
 *
 * Observable outcomes: normalized alpha, blurRadius, tintColor, and fallbackTintColor.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=ui-components --include-classes='*RadialOverlayBlurPolicyTest'`
 *   fails to compile until RadialOverlayBlurSpec and resolveRadialOverlayBlurSpec exist.
 * - GREEN: passes after ActionSheetTokens.kt defines the blur spec policy.
 *
 * Excludes: Jetpack Compose Popup rendering and GPU RenderEffect pixel output.
 */
class RadialOverlayBlurPolicyTest : UiComponentsFunSpec() {
    init {
        test("given effectsAlpha outside bounds when resolved then alpha clamps to zero and one") {
            val scheme = lightColorScheme()
            val under = resolveRadialOverlayBlurSpec(effectsAlpha = -0.35f, colorScheme = scheme)
            val over = resolveRadialOverlayBlurSpec(effectsAlpha = 1.75f, colorScheme = scheme)

            assertSoftly {
                under.alpha shouldBe 0f
                under.blurRadius shouldBe 20.dp
                over.alpha shouldBe 1f
                over.blurRadius shouldBe 20.dp
            }
        }

        test("given midpoint effectsAlpha when resolved then tint stays constant and fallback scales cleanly") {
            val scheme = lightColorScheme()
            val spec = resolveRadialOverlayBlurSpec(effectsAlpha = 0.5f, colorScheme = scheme)

            assertSoftly(spec) {
                alpha shouldBe 0.5f
                tintColor shouldBe scheme.scrim.copy(alpha = ActionSheetTokens.RadialScrimAlpha)
                fallbackTintColor shouldBe scheme.scrim.copy(alpha = ActionSheetTokens.RadialScrimAlpha * 0.5f)
            }
        }
    }
}
