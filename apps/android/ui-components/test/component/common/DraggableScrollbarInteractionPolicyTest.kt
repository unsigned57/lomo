package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: local dragging takes precedence over external scroll position; owner: ui-components; P1.
 * Scenarios:
 * - Given a drag, when external position changes, then the displayed offset remains local.
 * - Given no drag, when resolving the offset, then the settled animation position is displayed.
 * - Given a pointer, when testing the end touch zone, then only coordinates inside it are accepted.
 * Observable outcomes: displayed offset and touch-zone membership.
 * TDD proof: Regression preservation - scrollbar suite passed before and after refactor;
 * evidence: reports/manual/home-scroll/recomposition-green.log and scrollbar-regression.log.
 * Test Change Justification:
 * Reason category: behavior-preserving refactor.
 * Old behavior/assertion being replaced: idle drag-offset synchronization Boolean helper assertions.
 * Why old assertion is no longer correct: idle mirroring is removed; drag starts at the displayed
 * offset in the existing pointer path, so there is no external-sync operation to enable or disable.
 * Coverage preserved by: local/settled precedence below, DraggableScrollbarPressMappingTest,
 * ScrollbarRecompositionTest and device scroll/drag checks.
 * Why this is not fitting the test to the implementation: user-visible offset and hit-testing
 * assertions are unchanged; only assertions for a deleted redundant operation are removed.
 * Excludes: Android pointer dispatch and physical-device frame deadlines.
 */
class DraggableScrollbarInteractionPolicyTest : UiComponentsFunSpec() {
    init {
        test("dragging displays local thumb offset instead of external scroll offset") {
            (
                resolveDisplayedThumbOffsetPx(
                    isThumbDragged = true,
                    draggedThumbOffsetPx = 132f,
                    settledThumbOffsetPx = 84f,
                )
                ) shouldBe ((132f) plusOrMinus (0.001f))
        }

        test("idle state displays settled thumb offset") {
            (
                resolveDisplayedThumbOffsetPx(
                    isThumbDragged = false,
                    draggedThumbOffsetPx = 132f,
                    settledThumbOffsetPx = 84f,
                )
                ) shouldBe ((84f) plusOrMinus (0.001f))
        }

        test("point inside end-aligned touch zone is detected") {
            (
                isPointInScrollbarTouchZone(
                    pointerXPx = 980f,
                    canvasWidthPx = 1000f,
                    touchTargetWidthPx = 96f,
                )
                ) shouldBe true
        }

        test("point on touch-zone start boundary is included") {
            (
                isPointInScrollbarTouchZone(
                    pointerXPx = 904f,
                    canvasWidthPx = 1000f,
                    touchTargetWidthPx = 96f,
                )
                ) shouldBe true
        }

        test("point left of touch zone is rejected so memo cards keep gestures") {
            (
                isPointInScrollbarTouchZone(
                    pointerXPx = 800f,
                    canvasWidthPx = 1000f,
                    touchTargetWidthPx = 96f,
                )
                ) shouldBe false
        }

        test("negative pointer x is rejected") {
            (
                isPointInScrollbarTouchZone(
                    pointerXPx = -10f,
                    canvasWidthPx = 1000f,
                    touchTargetWidthPx = 96f,
                )
                ) shouldBe false
        }

        test("zero canvas width yields no touch zone so all presses are rejected") {
            (
                isPointInScrollbarTouchZone(
                    pointerXPx = 0f,
                    canvasWidthPx = 0f,
                    touchTargetWidthPx = 96f,
                )
                ) shouldBe false
        }

        test("touch target wider than canvas falls back to whole canvas") {
            (
                isPointInScrollbarTouchZone(
                    pointerXPx = 5f,
                    canvasWidthPx = 80f,
                    touchTargetWidthPx = 200f,
                )
                ) shouldBe true
        }
    }
}
