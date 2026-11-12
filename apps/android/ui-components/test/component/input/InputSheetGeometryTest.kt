package com.lomo.ui.component.input

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/*
 * Behavior Contract:
 * Capability: the input sheet adapts its compact card and long-form surface to the window width and
 *   interpolates every geometric property continuously; owning layer: ui-components input; priority: P1.
 * Scenarios:
 * - Given a phone-width window, when geometry resolves, then the compact card spans the width below the
 *   status bar and the long-form surface bleeds edge to edge with square corners under the status bar.
 * - Given a tablet-width window, when geometry resolves, then both forms are one centered, rounded card at
 *   the app's bottom-sheet width below the status bar, so the morph is purely vertical and text never reflows.
 * - Given windows around the bottom-sheet width, when geometry resolves, then both forms share one width
 *   that never exceeds the window.
 * - Given a phone morph sample, when geometry interpolates, then the content top edge stays on the status
 *   bar line so content never jumps under it.
 * - Given spring samples slightly outside 0..1, when geometry interpolates, then corner radius and insets
 *   stay non-negative and between the two forms.
 * Observable outcomes: surface width, top corner radius, top gap and content top inset per form and sample.
 * TDD proof: RED `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.input.InputSheetGeometryTest'`
 *   with the stub reproducing the old full-width, full-bleed sheet failed the tablet scenarios; GREEN after the
 *   width-aware geometry.
 * Excludes: Compose placement, predictive-back scaling, IME insets and spring tuning.
 *
 * Test Change Justification:
 * Reason category: domain contract change — discrete motion stages replaced by one continuous expansion.
 * Old behavior/assertion being replaced: InputSheetCornerRadiusPolicyTest (clampSheetCornerRadius),
 *   InputSheetAnimatedInsetsTest (InputSheetAnimatedInsets.top) and InputSheetMotionInsetsPolicyTest
 *   (InputSheetMotionStage.usesExpandedInsets); those units no longer exist.
 * Why old assertion is no longer correct: radius and insets are now interpolated from one clamped
 *   expansion fraction; there is no Collapsing stage whose inset policy could differ.
 * Coverage preserved by: the overshoot scenario (non-negative radius/insets, the old crash class) and the
 *   phone morph scenario (no second inset drop after collapse) below.
 * Why this is not fitting the test to the implementation: assertions state user-visible geometry for
 *   concrete window widths, not how the values are computed.
 */
class InputSheetGeometryTest : UiComponentsFunSpec() {
    init {
        val statusBar = 24.dp

        test("given a phone-width window when geometry resolves then the long-form surface bleeds edge to edge") {
            val geometry = resolveInputSheetGeometry(containerWidth = 411.dp, statusBarTop = statusBar)

            assertSoftly {
                geometry.compact shouldBe
                    InputSheetFormGeometry(
                        width = 411.dp,
                        cornerRadius = 28.dp,
                        topGap = statusBar,
                        contentTopInset = 0.dp,
                    )
                geometry.expanded shouldBe
                    InputSheetFormGeometry(
                        width = 411.dp,
                        cornerRadius = 0.dp,
                        topGap = 0.dp,
                        contentTopInset = statusBar,
                    )
            }
        }

        test("given a tablet-width window when geometry resolves then both forms are one centered rounded card") {
            val geometry = resolveInputSheetGeometry(containerWidth = 1_280.dp, statusBarTop = statusBar)
            val card = InputSheetFormGeometry(
                width = 640.dp,
                cornerRadius = 28.dp,
                topGap = statusBar + 24.dp,
                contentTopInset = 0.dp,
            )

            assertSoftly {
                geometry.compact shouldBe card
                geometry.expanded shouldBe card
            }
        }

        test(
            "given windows around the bottom-sheet width when geometry resolves then both forms share one fitting width",
        ) {
            for (width in listOf(360.dp, 600.dp, 640.dp, 641.dp, 700.dp, 900.dp)) {
                val geometry = resolveInputSheetGeometry(containerWidth = width, statusBarTop = statusBar)
                withClue("container=$width geometry=$geometry") {
                    geometry.expanded.width shouldBe geometry.compact.width
                    geometry.compact.width shouldBeLessThanOrEqualTo width
                    geometry.compact.width shouldBeLessThanOrEqualTo 640.dp
                }
            }
        }

        test(
            "given a phone morph sample when geometry interpolates then the content top edge stays on the status bar line",
        ) {
            val geometry = resolveInputSheetGeometry(containerWidth = 411.dp, statusBarTop = statusBar)
            for (step in 0..20) {
                val sample = geometry.at(step / 20f)
                withClue("sample=$sample") {
                    (sample.topGap + sample.contentTopInset).isCloseTo(statusBar) shouldBe true
                }
            }
        }

        test("given spring samples outside the unit interval when geometry interpolates then values stay legal") {
            val geometry = resolveInputSheetGeometry(containerWidth = 411.dp, statusBarTop = statusBar)

            assertSoftly {
                geometry.at(1.0004f) shouldBe geometry.expanded
                geometry.at(-0.0003f) shouldBe geometry.compact
                geometry.at(1.0004f).cornerRadius shouldBeGreaterThanOrEqualTo 0.dp
                geometry.at(-0.0003f).contentTopInset shouldBeGreaterThanOrEqualTo 0.dp
            }
        }
    }
}

private fun Dp.isCloseTo(other: Dp): Boolean = abs(value - other.value) < 0.01f
