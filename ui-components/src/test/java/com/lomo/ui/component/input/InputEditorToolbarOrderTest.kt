package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Input editor toolbar tool ordering policy.
 * - Behavior focus: undo and redo must remain available in the scrollable tool strip but move behind the content-insertion tools.
 * - Observable outcomes: resolved tool id ordering and relative positions of underline, undo, and redo.
 * - Red phase: Fails before the fix because undo and redo are still placed at the front of the tool strip.
 * - Excludes: icon rendering, enablement state wiring, and trailing send/expand actions.
 */
class InputEditorToolbarOrderTest {
    @Test
    fun `toolbar order moves undo and redo behind underline`() {
        val toolIds = inputToolbarToolIds()

        assertEquals(
            listOf("camera", "image", "record", "tag", "todo", "underline", "undo", "redo"),
            toolIds,
        )
        assertTrue(toolIds.indexOf("underline") < toolIds.indexOf("undo"))
        assertTrue(toolIds.indexOf("undo") < toolIds.indexOf("redo"))
    }
}
