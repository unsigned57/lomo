package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: draggable scrollbar interaction policy helpers.
 * - Behavior focus: dragging should keep the thumb on its local drag position instead of snapping
 *   back to externally recomputed scroll fractions, external thumb sync should stay disabled
 *   while a drag is active, and pointer events should be claimed by the scrollbar only when the
 *   initial touch falls inside its end-aligned touch zone.
 * - Observable outcomes: chosen displayed thumb offset, Boolean external-sync policy, and
 *   point-in-touch-zone classification.
 * - Red phase: Fails before the fix because the thumb is still drawn from the external scroll
 *   fraction during drag, which causes visible jump-back and makes dragging feel broken; and
 *   because there is no policy yet to decide whether a press near the right edge belongs to the
 *   scrollbar or to the underlying memo card.
 * - Excludes: Compose pointer input dispatch, LazyList measurement accuracy, and fade animation.
 */
class DraggableScrollbarInteractionPolicyTest : UiComponentsFunSpec() {
    init {
        test("dragging displays local thumb offset instead of external scroll offset") {
        (resolveDisplayedThumbOffsetPx(
                isThumbDragged = true,
                draggedThumbOffsetPx = 132f,
                settledThumbOffsetPx = 84f,
            )) shouldBe ((132f) plusOrMinus (0.001f))
        }
    }

    init {
        test("idle state displays settled thumb offset") {
        (resolveDisplayedThumbOffsetPx(
                isThumbDragged = false,
                draggedThumbOffsetPx = 132f,
                settledThumbOffsetPx = 84f,
            )) shouldBe ((84f) plusOrMinus (0.001f))
        }
    }

    init {
        test("external sync stays disabled while drag is active") {
        (shouldSyncDraggedThumbOffsetFromExternal(
                visible = true,
                isThumbDragged = true,
            )) shouldBe false
        }
    }

    init {
        test("external sync resumes after drag ends") {
        (shouldSyncDraggedThumbOffsetFromExternal(
                visible = true,
                isThumbDragged = false,
            )) shouldBe true
        }
    }

    init {
        test("point inside end-aligned touch zone is detected") {
        (isPointInScrollbarTouchZone(
                pointerXPx = 980f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            )) shouldBe true
        }
    }

    init {
        test("point on touch-zone start boundary is included") {
        (isPointInScrollbarTouchZone(
                pointerXPx = 904f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            )) shouldBe true
        }
    }

    init {
        test("point left of touch zone is rejected so memo cards keep gestures") {
        (isPointInScrollbarTouchZone(
                pointerXPx = 800f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            )) shouldBe false
        }
    }

    init {
        test("negative pointer x is rejected") {
        (isPointInScrollbarTouchZone(
                pointerXPx = -10f,
                canvasWidthPx = 1000f,
                touchTargetWidthPx = 96f,
            )) shouldBe false
        }
    }

    init {
        test("zero canvas width yields no touch zone so all presses are rejected") {
        (isPointInScrollbarTouchZone(
                pointerXPx = 0f,
                canvasWidthPx = 0f,
                touchTargetWidthPx = 96f,
            )) shouldBe false
        }
    }

    init {
        test("touch target wider than canvas falls back to whole canvas") {
        (isPointInScrollbarTouchZone(
                pointerXPx = 5f,
                canvasWidthPx = 80f,
                touchTargetWidthPx = 200f,
            )) shouldBe true
        }
    }
}
