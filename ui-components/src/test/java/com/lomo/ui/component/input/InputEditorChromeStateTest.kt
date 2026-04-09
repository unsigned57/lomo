package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputEditor long-form chrome state policy.
 * - Behavior focus: long-form entry should live in the toolbar as an icon action, and placeholder text should appear only for compact empty input.
 * - Observable outcomes: resolved toggle icon mode, standalone-mode-bar visibility, and placeholder visibility for compact and expanded editor states.
 * - Red phase: Fails before the fix because the editor renders a separate long-form mode bar and still shows placeholder text after expanding an empty draft.
 * - Excludes: Compose layout rendering, animation timing, and memo submission behavior.
 */
class InputEditorChromeStateTest {
    @Test
    fun `compact empty editor uses expand toolbar icon and shows placeholder`() {
        val state =
            resolveInputEditorChromeState(
                isExpanded = false,
                inputText = "",
                hintText = "Write something long",
            )

        assertEquals(InputEditorToggleIcon.Expand, state.toggleIcon)
        assertFalse(state.showsStandaloneModeBar)
        assertTrue(state.showsPlaceholder)
    }

    @Test
    fun `expanded empty editor uses collapse toolbar icon and hides placeholder`() {
        val state =
            resolveInputEditorChromeState(
                isExpanded = true,
                inputText = "",
                hintText = "Write something long",
            )

        assertEquals(InputEditorToggleIcon.Collapse, state.toggleIcon)
        assertFalse(state.showsStandaloneModeBar)
        assertFalse(state.showsPlaceholder)
    }
}
