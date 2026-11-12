package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: the input sheet header shares one row between the start content, the drag handle and the
 *   undo/redo/expand cluster; the handle owns a centered slot in the compact card and returns its width
 *   to the mode switch in the long form; owning layer: ui-components input; priority: P2.
 * Scenarios:
 * - Given the compact card, when the header resolves, then the handle slot keeps its full width and the
 *   header adds only the short compact top inset instead of a separate grip strip.
 * - Given the long form, when the header resolves, then the handle slot collapses to zero width and the
 *   top inset is the long form's full top padding.
 * - Given spring samples outside 0..1, when the header resolves, then slot width and inset stay between
 *   the two forms.
 * Observable outcomes: handle slot width and header top inset in pixels per expansion sample.
 * TDD proof: RED `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.input.InputSheetHeaderLayoutPolicyTest'`
 *   failed to compile before the header policy existed (the old header stacked a 22 dp grip strip above
 *   a separate action-cluster row); GREEN after the shared-row policy.
 * Excludes: Compose drawing of the handle, icon tinting, drag gestures and spring tuning.
 */
class InputSheetHeaderLayoutPolicyTest : UiComponentsFunSpec() {
    init {
        test("given the compact card when the header resolves then the handle keeps its slot under a short inset") {
            assertSoftly {
                resolveInputSheetHandleSlotWidthPx(fraction = 0f, slotPx = 104f) shouldBe 104
                resolveInputSheetHeaderTopInsetPx(fraction = 0f, compactPx = 8f, expandedPx = 32f) shouldBe 8
            }
        }

        test("given the long form when the header resolves then the handle slot is gone and the inset is full") {
            assertSoftly {
                resolveInputSheetHandleSlotWidthPx(fraction = 1f, slotPx = 104f) shouldBe 0
                resolveInputSheetHeaderTopInsetPx(fraction = 1f, compactPx = 8f, expandedPx = 32f) shouldBe 32
            }
        }

        test("given spring overshoot when the header resolves then nothing leaves the two forms") {
            for (fraction in listOf(-0.2f, 0.5f, 1.3f)) {
                withClue("fraction=$fraction") {
                    val width = resolveInputSheetHandleSlotWidthPx(fraction = fraction, slotPx = 104f)
                    val inset = resolveInputSheetHeaderTopInsetPx(fraction = fraction, compactPx = 8f, expandedPx = 32f)
                    assertSoftly {
                        (width in 0..104) shouldBe true
                        (inset in 8..32) shouldBe true
                    }
                }
            }
            resolveInputSheetHandleSlotWidthPx(fraction = 0.5f, slotPx = 104f) shouldBe 52
        }
    }
}
