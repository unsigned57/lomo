package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given a press inside the thumb including its bottom boundary, when drag start is resolved, then dragging
 *   begins from the current thumb offset.
 * - Given a press outside the thumb, when drag start is resolved, then a track jump is not started.
 * - Unit under test: resolveThumbDragStartOffsetFromPress
 * - Capability: The scrollbar must start a drag only when the first press lands on the
 *   visible thumb. Presses on empty rail space must be ignored so memo-card menu taps near the
 *   right edge cannot trigger a scrollbar jump.
 * - Observable outcomes: nullable drag-start thumb offset in pixels.
 * - TDD proof: Fails before the fix because track presses are still mapped to a centered thumb
 *   offset, which makes right-edge memo menu taps jump the list instead of staying with the card.
 * - Excludes: Compose gesture dispatch (long-press detection, drag dispatch), scroll fraction
 *   math, thumb fade animation, systemGestureExclusion routing.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for DraggableScrollbarPressMapping.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including press on thumb starts drag from current thumb
 * offset; press on thumb bottom boundary starts drag.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
/*
 * Test Change Justification:
 * - Reason category: user-visible interaction contract change.
 * - Old behavior/assertion being replaced: pressing an empty part of the scrollbar track centered
 *   the thumb on that press and immediately jumped the list.
 * - Why old assertion is no longer correct: the accepted fix disables track jumping because it
 *   steals taps intended for memo menu buttons near the right edge.
 * - Coverage preserved by: thumb presses still assert that a drag can begin from the current
 *   offset, while empty-track presses now assert the new ignore behavior.
 * - Why this is not fitting the test to the implementation: the new assertions encode the chosen
 *   product interaction, not a workaround for current code.
 */
class DraggableScrollbarPressMappingTest : UiComponentsFunSpec() {
    init {
        test("press on thumb starts drag from current thumb offset") {
            val offset =
                resolveThumbDragStartOffsetFromPress(
                    pressY = 55f,
                    currentThumbOffsetPx = 40f,
                    thumbExtentPx = 60f,
                    trackHeightPx = 400f,
                )
            (offset ?: error("Expected thumb press to start drag")) shouldBe ((40f) plusOrMinus (0.001f))
        }
    }

    init {
        test("press on thumb bottom boundary starts drag") {
            val offset =
                resolveThumbDragStartOffsetFromPress(
                    pressY = 100f,
                    currentThumbOffsetPx = 40f,
                    thumbExtentPx = 60f,
                    trackHeightPx = 400f,
                )
            (offset ?: error("Expected thumb boundary press to start drag")) shouldBe ((40f) plusOrMinus (0.001f))
        }
    }

    init {
        test("press above thumb ignores track jump") {
            val offset =
                resolveThumbDragStartOffsetFromPress(
                    pressY = 20f,
                    currentThumbOffsetPx = 200f,
                    thumbExtentPx = 60f,
                    trackHeightPx = 400f,
                )
            (offset) shouldBe null
        }
    }

    init {
        test("press below thumb ignores track jump") {
            val offset =
                resolveThumbDragStartOffsetFromPress(
                    pressY = 200f,
                    currentThumbOffsetPx = 40f,
                    thumbExtentPx = 60f,
                    trackHeightPx = 400f,
                )
            (offset) shouldBe null
        }
    }

    init {
        test("press with zero track height starts no drag") {
            val offset =
                resolveThumbDragStartOffsetFromPress(
                    pressY = 50f,
                    currentThumbOffsetPx = 0f,
                    thumbExtentPx = 60f,
                    trackHeightPx = 0f,
                )
            (offset) shouldBe null
        }
    }
}
