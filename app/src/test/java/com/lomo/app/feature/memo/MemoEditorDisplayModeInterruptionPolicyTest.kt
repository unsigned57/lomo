package com.lomo.app.feature.memo

import com.lomo.ui.component.input.InputEditorDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEditorController preview interruption policy.
 * - Behavior focus: returning from preview to edit should request focus once, and collapsing the
 *   long-form session immediately afterwards must not issue a second focus request while resetting
 *   the display mode back to edit.
 * - Observable outcomes: focusRequestToken progression and final displayMode after preview -> edit
 *   -> collapse sequencing.
 * - Red phase: Fails before the fix because preview/display-mode coordination is incomplete, so
 *   collapse handling can only be inferred from expanded mode and may retrigger focus acquisition
 *   instead of cleanly resetting to compact edit.
 * - Excludes: Compose sheet rendering, IME activation timing, and markdown preview rendering.
 */
class MemoEditorDisplayModeInterruptionPolicyTest {
    @Test
    fun `preview return followed by collapse keeps a single focus request`() {
        val controller = MemoEditorController()

        controller.openForCreate("draft")
        controller.setExpanded(true)
        controller.updateDisplayMode(InputEditorDisplayMode.Preview)

        val previewToken = controller.focusRequestToken

        controller.updateDisplayMode(InputEditorDisplayMode.Edit)
        controller.setExpanded(false)

        assertEquals(InputEditorDisplayMode.Edit, controller.displayMode)
        assertEquals(MemoEditorMode.Compact, controller.mode)
        assertEquals(previewToken + 1L, controller.focusRequestToken)
    }
}
