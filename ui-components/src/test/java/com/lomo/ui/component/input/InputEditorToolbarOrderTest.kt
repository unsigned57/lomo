package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Input editor toolbar tool ordering policy.
 * - Behavior focus: undo and redo must remain behind the content-insertion tools, backfill must be part of the default toolbar, and persisted toolbar order must be normalized.
 * - Observable outcomes: resolved tool id ordering, relative positions, and filtering of duplicate/unknown persisted ids.
 * - Red phase: Fails before the fix because undo and redo are still placed at the front of the tool strip.
 * - Excludes: icon rendering, enablement state wiring, and trailing send/expand actions.
 */
class InputEditorToolbarOrderTest {
    @Test
    fun `toolbar order places backfill before content formatting and undo redo behind underline`() {
        val toolIds = inputToolbarToolIds()

        assertEquals(
            listOf("camera", "image", "record", "tag", "location", "backfill", "todo", "underline", "undo", "redo"),
            toolIds,
        )
        assertTrue(toolIds.indexOf("location") < toolIds.indexOf("backfill"))
        assertTrue(toolIds.indexOf("backfill") < toolIds.indexOf("todo"))
        assertTrue(toolIds.indexOf("underline") < toolIds.indexOf("undo"))
        assertTrue(toolIds.indexOf("undo") < toolIds.indexOf("redo"))
    }

    @Test
    fun `persisted toolbar order is deduplicated and completed with default tools`() {
        val toolIds =
            resolveInputToolbarToolIds(
                persistedOrder = listOf("backfill", "camera", "unknown", "backfill", "todo"),
            )

        assertEquals(
            listOf("backfill", "camera", "todo", "image", "record", "tag", "location", "underline", "undo", "redo"),
            toolIds,
        )
    }

    @Test
    fun `backfill button enablement ignores submit state`() {
        assertTrue(
            resolveInputToolbarBackfillEnabled(
                toolbarEnabled = true,
                isBackfillEnabled = true,
            ),
        )
    }
}
