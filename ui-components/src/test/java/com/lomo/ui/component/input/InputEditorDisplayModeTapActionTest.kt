package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Test

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
class InputEditorDisplayModeTapActionTest {
    @Test
    fun `repeated tap on current edit mode collapses`() {
        val action =
            resolveInputEditorDisplayModeTapAction(
                currentMode = InputEditorDisplayMode.Edit,
                tappedMode = InputEditorDisplayMode.Edit,
            )

        assertEquals(InputEditorDisplayModeTapAction.Collapse, action)
    }

    @Test
    fun `repeated tap on current preview mode collapses`() {
        val action =
            resolveInputEditorDisplayModeTapAction(
                currentMode = InputEditorDisplayMode.Preview,
                tappedMode = InputEditorDisplayMode.Preview,
            )

        assertEquals(InputEditorDisplayModeTapAction.Collapse, action)
    }

    @Test
    fun `tapping the other mode switches without collapsing`() {
        val action =
            resolveInputEditorDisplayModeTapAction(
                currentMode = InputEditorDisplayMode.Edit,
                tappedMode = InputEditorDisplayMode.Preview,
            )

        assertEquals(
            InputEditorDisplayModeTapAction.ChangeMode(InputEditorDisplayMode.Preview),
            action,
        )
    }
}
