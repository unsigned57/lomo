package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: the editor canvas morphs between the compact 3..6-line card and the long-form fill along
 *   one uniform path; owning layer: ui-components input; priority: P1.
 * Scenarios:
 * - Given the compact form, when the canvas range resolves, then text wraps between the 3- and 6-line heights.
 * - Given the long-form, when the canvas range resolves, then both bounds equal the available height.
 * - Given short, medium and tall text and expansion advancing in 1% steps, when the canvas height is realized,
 *   then it starts at the text's own compact height, ends at the available height and advances by the same
 *   share on every step: no first-frame jump (the old layout switched to "fill" at once) and no mid-flight stall.
 * - Given a window shorter than the compact minimum, when the range resolves, then it never exceeds the space.
 * - Given a spring sample outside 0..1, when the range resolves, then it stays between the two forms.
 * Observable outcomes: the returned min/max canvas heights in pixels and the height they realize for a text.
 * TDD proof: RED `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.input.InputEditorCanvasHeightPolicyTest'`
 *   with the stub reproducing the old wrap-or-fill switch failed the uniform-path scenario with a 1280px jump at
 *   the first step and the cramped-window scenario (240 should be <= 80); GREEN after interpolation.
 * Excludes: Compose measurement of the text field, IME insets and spring tuning.
 */
class InputEditorCanvasHeightPolicyTest : UiComponentsFunSpec() {
    init {
        val compactMin = 120
        val compactMax = 240
        val available = 1_400

        test("given the compact form when the canvas range resolves then text wraps between the line bounds") {
            resolveInputEditorCanvasHeightRange(
                0f,
                compactMin,
                compactMax,
                contentPx = 180,
                availablePx = available,
            ) shouldBe
                InputEditorCanvasHeightRange(minPx = compactMin, maxPx = compactMax)
        }

        test("given the long-form when the canvas range resolves then the canvas fills the available height") {
            resolveInputEditorCanvasHeightRange(
                1f,
                compactMin,
                compactMax,
                contentPx = 180,
                availablePx = available,
            ) shouldBe
                InputEditorCanvasHeightRange(minPx = available, maxPx = available)
        }

        test("given text of any length when expansion advances in small steps then the canvas moves uniformly") {
            for (contentPx in listOf(48, 180, 900)) {
                val start = contentPx.coerceIn(compactMin, compactMax)
                val share = (available - start) / 100
                var previous = realizedCanvasHeight(0f, compactMin, compactMax, contentPx, available)
                withClue("content=$contentPx starts at its compact height") { previous shouldBe start }
                for (step in 1..100) {
                    val current = realizedCanvasHeight(step / 100f, compactMin, compactMax, contentPx, available)
                    withClue("content=$contentPx expansion=${step / 100f} previous=$previous current=$current") {
                        (current - previous) shouldBeInRange (share - 1)..(share + 1)
                    }
                    previous = current
                }
                withClue("content=$contentPx ends at the available height") { previous shouldBe available }
            }
        }

        test("given a window shorter than the compact minimum when the range resolves then it stays inside the space") {
            val cramped = 80
            for (expansion in listOf(0f, 0.5f, 1f)) {
                val range = resolveInputEditorCanvasHeightRange(
                    expansion,
                    compactMin,
                    compactMax,
                    contentPx = 180,
                    availablePx = cramped,
                )
                withClue("expansion=$expansion range=$range") {
                    assertSoftly {
                        range.maxPx shouldBeLessThanOrEqual cramped
                        range.minPx shouldBeLessThanOrEqual range.maxPx
                        range.minPx shouldBeGreaterThanOrEqual 0
                    }
                }
            }
        }

        test(
            "given a spring sample outside the unit interval when the range resolves then it stays between the forms",
        ) {
            resolveInputEditorCanvasHeightRange(
                1.003f,
                compactMin,
                compactMax,
                contentPx = 180,
                availablePx = available,
            ) shouldBe
                InputEditorCanvasHeightRange(minPx = available, maxPx = available)
            resolveInputEditorCanvasHeightRange(
                -0.002f,
                compactMin,
                compactMax,
                contentPx = 180,
                availablePx = available,
            ) shouldBe
                InputEditorCanvasHeightRange(minPx = compactMin, maxPx = compactMax)
        }
    }
}

/** The text field measures its content inside the range: this is the height the user sees. */
private fun realizedCanvasHeight(
    expansion: Float,
    compactMin: Int,
    compactMax: Int,
    contentPx: Int,
    available: Int,
): Int {
    val range = resolveInputEditorCanvasHeightRange(expansion, compactMin, compactMax, contentPx, available)
    return contentPx.coerceIn(range.minPx, range.maxPx)
}
