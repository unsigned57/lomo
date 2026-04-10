package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet long-form presentation state policy.
 * - Behavior focus: long-form expand/collapse and edit/preview switching must resolve through one
 *   presentation state machine so content visibility, toolbar chrome, and focus intent stay in
 *   sync during transitions.
 * - Observable outcomes: requested and settled presentation states, derived surface motion stage,
 *   editor/preview visibility policy, and editor focus ownership policy.
 * - Red phase: Fails before the fix because InputSheet has no unified presentation state machine
 *   for preview transitions, so preview is not part of the same state model as expand/collapse and
 *   focus release happens out-of-band.
 * - Excludes: Compose animation timing, Android IME internals, and markdown rendering output.
 */
class InputSheetPresentationStateTest {
    @Test
    fun `expanded edit transitions into preview through dedicated switching state`() {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Preview,
                currentState = InputSheetPresentationState.ExpandedEdit,
            )

        assertEquals(InputSheetPresentationState.SwitchingToPreview, requested)
        assertTrue(requested.showsEditorContent())
        assertTrue(requested.showsPreviewLayer())
        assertFalse(requested.prefersEditorFocus())
        assertEquals(InputSheetMotionStage.Expanded, requested.surfaceMotionStage())
    }

    @Test
    fun `preview settles as expanded preview and collapse keeps preview stable until compact transition ends`() {
        val settledPreview =
            resolveSettledInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Preview,
            )
        val collapsing =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = false,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.SwitchingToPreview,
            )

        assertEquals(InputSheetPresentationState.ExpandedPreview, settledPreview)
        assertFalse(settledPreview.prefersEditorFocus())
        assertEquals(InputSheetPresentationState.CollapsingFromPreview, collapsing)
        assertFalse(collapsing.showsEditorContent())
        assertTrue(collapsing.showsPreviewLayer())
        assertFalse(collapsing.prefersEditorFocus())
    }

    @Test
    fun `preview returning to edit uses switching state before regaining editor focus`() {
        val requested =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Edit,
                currentState = InputSheetPresentationState.ExpandedPreview,
            )
        val settled =
            resolveSettledInputSheetPresentationState(
                targetExpanded = true,
                targetDisplayMode = InputEditorDisplayMode.Edit,
            )

        assertEquals(InputSheetPresentationState.SwitchingToEdit, requested)
        assertTrue(requested.showsEditorContent())
        assertTrue(requested.showsPreviewLayer())
        assertFalse(requested.prefersEditorFocus())
        assertEquals(InputSheetPresentationState.ExpandedEdit, settled)
        assertTrue(settled.prefersEditorFocus())
    }
}
