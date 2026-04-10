package com.lomo.app.feature.memo

import com.lomo.ui.component.input.InputEditorDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEditorController preview display-mode policy.
 * - Behavior focus: long-form editor sessions should default to edit mode, allow switching into preview without re-requesting focus, re-request focus when returning to edit, and reset preview mode on collapse or close.
 * - Observable outcomes: displayMode value and focusRequestToken progression across preview transitions, collapse, and close.
 * - Red phase: Fails before the fix because MemoEditorController has no preview display-mode state, cannot switch between edit and preview, and cannot reset preview mode when the long-form session collapses or closes.
 * - Excludes: Compose sheet rendering, keyboard controller internals, and markdown renderer output.
 */
class MemoEditorDisplayModePolicyTest {
    @Test
    fun `preview transitions keep editor session consistent and reset on collapse or close`() {
        val controller = MemoEditorController()

        controller.openForCreate("draft")
        val initialFocusRequestToken = controller.focusRequestToken

        assertEquals(InputEditorDisplayMode.Edit, controller.displayMode)

        controller.setExpanded(true)
        controller.updateDisplayMode(InputEditorDisplayMode.Preview)

        assertEquals(InputEditorDisplayMode.Preview, controller.displayMode)
        assertEquals(initialFocusRequestToken, controller.focusRequestToken)

        controller.updateDisplayMode(InputEditorDisplayMode.Edit)

        assertEquals(InputEditorDisplayMode.Edit, controller.displayMode)
        assertEquals(initialFocusRequestToken + 1L, controller.focusRequestToken)

        controller.updateDisplayMode(InputEditorDisplayMode.Preview)
        controller.setExpanded(false)
        assertEquals(InputEditorDisplayMode.Edit, controller.displayMode)

        controller.setExpanded(true)
        controller.updateDisplayMode(InputEditorDisplayMode.Preview)
        controller.close()
        assertEquals(InputEditorDisplayMode.Edit, controller.displayMode)
    }
}
