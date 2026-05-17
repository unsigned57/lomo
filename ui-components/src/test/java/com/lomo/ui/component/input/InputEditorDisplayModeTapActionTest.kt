package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: InputEditor display-mode tap action policy.
 * - Behavior focus: tapping the currently selected long-form mode should collapse the sheet, while
 *   tapping the other mode should switch modes without closing.
 * - Observable outcomes: resolved tap action for same-mode and cross-mode taps.
 * - Red phase: Fails before the fix because the display-mode pills only re-send the same mode value
 *   and never resolve a collapse action for repeated taps.
 * - Excludes: Compose click handling, animation timing, and controller persistence.
 */
class InputEditorDisplayModeTapActionTest : UiComponentsFunSpec() {
    init {
        test("repeated tap on current edit mode collapses") {
        val action =
            resolveInputEditorDisplayModeTapAction(
                currentMode = InputEditorDisplayMode.Edit,
                tappedMode = InputEditorDisplayMode.Edit,
            )

        (action) shouldBe (InputEditorDisplayModeTapAction.Collapse)
        }
    }

    init {
        test("repeated tap on current preview mode collapses") {
        val action =
            resolveInputEditorDisplayModeTapAction(
                currentMode = InputEditorDisplayMode.Preview,
                tappedMode = InputEditorDisplayMode.Preview,
            )

        (action) shouldBe (InputEditorDisplayModeTapAction.Collapse)
        }
    }

    init {
        test("tapping the other mode switches without collapsing") {
        val action =
            resolveInputEditorDisplayModeTapAction(
                currentMode = InputEditorDisplayMode.Edit,
                tappedMode = InputEditorDisplayMode.Preview,
            )

        (action) shouldBe (InputEditorDisplayModeTapAction.ChangeMode(InputEditorDisplayMode.Preview))
        }
    }
}
