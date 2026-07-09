package com.lomo.ui.component.picker

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SteppedPickerPaging
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: drive the shared multi-step picker footer so a composite date/time/option dialog
 *   (reminder, backfill) advances through its steps one surface at a time and only emits a confirm on
 *   the final step, instead of stacking every picker into one over-tall dialog whose footer button
 *   scrolls off screen.
 *
 * Scenarios:
 * - Given a multi-step flow positioned before the final step, when the footer is resolved, then the step
 *   is not the last and advancing moves to the next index.
 * - Given a multi-step flow positioned on the final step, when the footer is resolved, then the step is
 *   the last and advancing offers no next index, so the caller confirms.
 * - Given a single-step flow, when the footer is resolved, then the only step is already the last and the
 *   footer confirms immediately.
 *
 * Observable outcomes:
 * - isLastStep boolean and nextStepIndex value (next index, or null when the caller should confirm).
 *
 * TDD proof:
 * - Red phase fails to resolve before the fix because no shared SteppedPickerPaging owns the
 *   advance-until-last-then-confirm boundary; the reminder dialog held it privately and backfill had none.
 *
 * Excludes:
 * - AnimatedContent rendering, footer button styling, and the concrete date/time/option picker surfaces.
 */
class SteppedPickerPagingTest : UiComponentsFunSpec() {
    init {
        test("given a step before the last when advancing then it moves to the next step") {
            SteppedPickerPaging.isLastStep(currentIndex = 0, stepCount = 3) shouldBe false
            SteppedPickerPaging.nextStepIndex(currentIndex = 0, stepCount = 3) shouldBe 1
            SteppedPickerPaging.nextStepIndex(currentIndex = 1, stepCount = 3) shouldBe 2
        }

        test("given the final step when advancing then no next step is offered so the caller confirms") {
            SteppedPickerPaging.isLastStep(currentIndex = 2, stepCount = 3) shouldBe true
            SteppedPickerPaging.nextStepIndex(currentIndex = 2, stepCount = 3) shouldBe null
        }

        test("given a single step flow then the only step is already the last") {
            SteppedPickerPaging.isLastStep(currentIndex = 0, stepCount = 1) shouldBe true
            SteppedPickerPaging.nextStepIndex(currentIndex = 0, stepCount = 1) shouldBe null
        }
    }
}
