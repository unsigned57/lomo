package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet collapse stability policy.
 * - Behavior focus: closing expanded long-form mode must keep the selected mode bar and the active
 *   content layer stable throughout the collapse transition so the UI does not stutter on the first frame.
 * - Observable outcomes: requested collapse state, mode-bar visibility, preview-layer visibility, and focus policy.
 * - Red phase: Fails before the fix because InputSheet uses a single collapse state that immediately
 *   drops the mode bar and preview semantics instead of preserving them through the collapse transition.
 * - Excludes: Compose frame pacing, IME physics, and markdown rendering internals.
 */
class InputSheetCollapseStabilityPolicyTest {
    @Test
    fun `expanded edit collapse keeps display mode bar visible during collapse`() {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.ExpandedEdit,
            )

        assertEquals(InputSheetPresentationState.CollapsingFromEdit, requested)
        assertTrue(requested.showsDisplayModeToggle())
        assertTrue(requested.showsEditorContent())
        assertFalse(requested.showsPreviewLayer())
        assertTrue(requested.prefersEditorFocus())
    }

    @Test
    fun `expanded preview collapse keeps preview layer until compact state settles`() {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.ExpandedPreview,
            )

        assertEquals(InputSheetPresentationState.CollapsingFromPreview, requested)
        assertTrue(requested.showsDisplayModeToggle())
        assertFalse(requested.showsEditorContent())
        assertTrue(requested.showsPreviewLayer())
        assertFalse(requested.prefersEditorFocus())
    }

    @Test
    fun `switching back to edit still collapses through preview-stable path`() {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.SwitchingToEdit,
            )

        assertEquals(InputSheetPresentationState.CollapsingFromPreview, requested)
        assertTrue(requested.showsDisplayModeToggle())
        assertTrue(requested.showsPreviewLayer())
        assertFalse(requested.prefersEditorFocus())
    }
}
