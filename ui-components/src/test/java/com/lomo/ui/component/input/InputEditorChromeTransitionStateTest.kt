package com.lomo.ui.component.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputEditor chrome transition state policy.
 * - Behavior focus: long-form edit/preview switching should keep stable chrome hosts mounted while
 *   disabling only the formatting toolbar during preview, and should restore the toolbar as soon as
 *   the sheet starts transitioning back to edit mode.
 * - Observable outcomes: resolved display-mode bar host retention, formatting-toolbar host
 *   retention, visual visibility flags, and interaction enablement across expanded edit, preview,
 *   and transition states.
 * - Red phase: Fails before the fix because preview transitions directly unmount the formatting
 *   toolbar host and keep it hidden through `SwitchingToEdit`, so the bottom action area jumps
 *   instead of recovering smoothly.
 * - Excludes: Compose frame timing, actual alpha interpolation values, and markdown rendering.
 */
class InputEditorChromeTransitionStateTest {
    @Test
    fun `expanded edit keeps mode bar and formatting toolbar visible`() {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.ExpandedEdit,
                inputText = "Draft",
                hintText = "Hint",
            )

        assertTrue(state.displayModeBar.keepsHostMounted)
        assertTrue(state.displayModeBar.isVisible)
        assertTrue(state.displayModeBar.isInteractive)
        assertTrue(state.formattingToolbar.keepsHostMounted)
        assertTrue(state.formattingToolbar.isVisible)
        assertTrue(state.formattingToolbar.isInteractive)
    }

    @Test
    fun `switching to preview keeps chrome hosts mounted while hiding formatting toolbar`() {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.SwitchingToPreview,
                inputText = "Draft",
                hintText = "Hint",
            )

        assertTrue(state.displayModeBar.keepsHostMounted)
        assertTrue(state.displayModeBar.isVisible)
        assertTrue(state.displayModeBar.isInteractive)
        assertTrue(state.formattingToolbar.keepsHostMounted)
        assertFalse(state.formattingToolbar.isVisible)
        assertFalse(state.formattingToolbar.isInteractive)
    }

    @Test
    fun `expanded preview keeps mode bar host and disables formatting toolbar`() {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.ExpandedPreview,
                inputText = "Draft",
                hintText = "Hint",
            )

        assertTrue(state.displayModeBar.keepsHostMounted)
        assertTrue(state.displayModeBar.isVisible)
        assertTrue(state.displayModeBar.isInteractive)
        assertTrue(state.formattingToolbar.keepsHostMounted)
        assertFalse(state.formattingToolbar.isVisible)
        assertFalse(state.formattingToolbar.isInteractive)
    }

    @Test
    fun `switching to edit starts restoring formatting toolbar without remounting host`() {
        val state =
            resolveInputEditorChromeState(
                presentationState = InputSheetPresentationState.SwitchingToEdit,
                inputText = "Draft",
                hintText = "Hint",
            )

        assertTrue(state.displayModeBar.keepsHostMounted)
        assertTrue(state.displayModeBar.isVisible)
        assertTrue(state.displayModeBar.isInteractive)
        assertTrue(state.formattingToolbar.keepsHostMounted)
        assertTrue(state.formattingToolbar.isVisible)
        assertTrue(state.formattingToolbar.isInteractive)
    }
}
