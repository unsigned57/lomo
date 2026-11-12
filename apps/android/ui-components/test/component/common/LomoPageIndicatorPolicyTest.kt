package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * Capability: resolve the M3 Expressive page-indicator variant and active page from a pager
 * position; owning layer: ui-components/common; priority: P1.
 * Scenarios:
 * - Given a single page (or none), when the indicator state is resolved, then it is Hidden.
 * - Given five or fewer pages, when the state is resolved, then the Dots variant is used and the
 *   active index equals the current page.
 * - Given six or more pages, when the state is resolved, then the Compact variant is used
 *   (M3E switches to the compact indicator beyond five pages).
 * - Given a current index outside [0, total), when the state is resolved, then the active index is
 *   clamped into range so a mid-reload pager can never highlight a nonexistent page.
 * Observable outcomes: returned variant, active index and page count.
 * TDD proof: RED before implementation — `resolveLomoPageIndicatorState` did not exist; both
 * pagers rendered bare "n / total" text with no variant policy. GREEN after adding the component.
 * Excludes: Compose layout, dot animation timing and media-overlay colors.
 */
class LomoPageIndicatorPolicyTest : UiComponentsFunSpec() {
    init {
        test("given zero pages when resolved then the indicator is hidden") {
            resolveLomoPageIndicatorState(current = 0, total = 0).variant shouldBe
                LomoPageIndicatorVariant.Hidden
        }

        test("given a single page when resolved then the indicator is hidden") {
            resolveLomoPageIndicatorState(current = 0, total = 1).variant shouldBe
                LomoPageIndicatorVariant.Hidden
        }

        test("given five pages when resolved then dots are used with the current page active") {
            val state = resolveLomoPageIndicatorState(current = 3, total = 5)

            state.variant shouldBe LomoPageIndicatorVariant.Dots
            state.activeIndex shouldBe 3
            state.pageCount shouldBe 5
        }

        test("given six pages when resolved then the compact variant is used") {
            resolveLomoPageIndicatorState(current = 0, total = 6).variant shouldBe
                LomoPageIndicatorVariant.Compact
        }

        test("given an out-of-range current page when resolved then the active index clamps") {
            val beyondLast = resolveLomoPageIndicatorState(current = 9, total = 4)
            val belowFirst = resolveLomoPageIndicatorState(current = -2, total = 4)

            beyondLast.activeIndex shouldBe 3
            belowFirst.activeIndex shouldBe 0
            beyondLast.shouldBeInstanceOf<LomoPageIndicatorState>()
        }
    }
}
