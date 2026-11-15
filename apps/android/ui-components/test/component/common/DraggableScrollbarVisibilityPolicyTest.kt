package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given idle, scrolling, or dragging input, when visual state is resolved, then drag takes priority and
 *   each state selects its designated alpha.
 * - Given scrollable content at rest, when idle alpha is resolved, then it remains positive and lower than
 *   active alpha.
 * - Unit under test: draggable scrollbar visual-state policy helpers.
 * - Capability: the scrollbar must always be discoverable for scrollable content (idle thumb
 *   is faintly visible) and must escalate its visual prominence based on interaction state, with
 *   active drag winning over passive scrolling and idle being the most subdued.
 * - Observable outcomes: resolved [ScrollbarThumbVisualState] and the alpha value mapped from it.
 * - TDD proof: Fails before the fix because the scrollbar relies on a single idle alpha value
 *   and on a "recently scrolled" gate that hides the thumb when the list is at rest, leaving
 *   users without any affordance to grab.
 * - Excludes: AnimatedVisibility timing, color sourcing from the M3 color scheme, and width
 *   animation.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for DraggableScrollbarVisibilityPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including idle visual state when nothing is happening;
 * scroll in progress upgrades to active state.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class DraggableScrollbarVisibilityPolicyTest : UiComponentsFunSpec() {
    init {
        test("idle visual state when nothing is happening") {
            (
                resolveScrollbarThumbVisualState(
                    isThumbDragged = false,
                    isScrollInProgress = false,
                    recentlyScrolled = false,
                )
                ) shouldBe (ScrollbarThumbVisualState.Idle)
        }
    }

    init {
        test("scroll in progress upgrades to active state") {
            (
                resolveScrollbarThumbVisualState(
                    isThumbDragged = false,
                    isScrollInProgress = true,
                    recentlyScrolled = false,
                )
                ) shouldBe (ScrollbarThumbVisualState.Active)
        }
    }

    init {
        test("recently scrolled stays in active state during fade-out window") {
            (
                resolveScrollbarThumbVisualState(
                    isThumbDragged = false,
                    isScrollInProgress = false,
                    recentlyScrolled = true,
                )
                ) shouldBe (ScrollbarThumbVisualState.Active)
        }
    }

    init {
        test("thumb dragged wins over scroll in progress") {
            (
                resolveScrollbarThumbVisualState(
                    isThumbDragged = true,
                    isScrollInProgress = true,
                    recentlyScrolled = true,
                )
                ) shouldBe (ScrollbarThumbVisualState.Drag)
        }
    }

    init {
        test("idle alpha is strictly lower than active alpha") {
            val idle = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Idle)
            val active = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Active)
            withClue("idle alpha must stay below active alpha") { (idle < active) shouldBe true }
        }
    }

    init {
        test("drag alpha is highest") {
            val active = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Active)
            val drag = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Drag)
            withClue("drag alpha must rise above active alpha") { (drag > active) shouldBe true }
        }
    }

    init {
        test("idle alpha stays positive so users can discover the scrollbar at rest") {
            (resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Idle) > 0f) shouldBe true
        }
    }

    init {
        test("each visual state maps to a distinct alpha") {
            val idle = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Idle)
            val active = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Active)
            val drag = resolveScrollbarThumbAlpha(ScrollbarThumbVisualState.Drag)
            (idle) shouldNotBe (active)
            (active) shouldNotBe (drag)
            (idle) shouldNotBe (drag)
        }
    }
}
