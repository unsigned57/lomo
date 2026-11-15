package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given the currently selected edit or preview mode, when its selector is tapped again, then the editor
 *   collapses.
 * - Given the other display mode, when its selector is tapped, then the mode changes without collapsing.
 * - Unit under test: InputEditor display-mode tap action policy.
 * - Capability: tapping the currently selected long-form mode should collapse the sheet, while
 *   tapping the other mode should switch modes without closing.
 * - Observable outcomes: resolved tap action for same-mode and cross-mode taps.
 * - TDD proof: Fails before the fix because the display-mode pills only re-send the same mode value
 *   and never resolve a collapse action for repeated taps.
 * - Excludes: Compose click handling, animation timing, and controller persistence.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for InputEditorDisplayModeTapAction.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including repeated tap on current edit mode collapses;
 * repeated tap on current preview mode collapses.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
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
