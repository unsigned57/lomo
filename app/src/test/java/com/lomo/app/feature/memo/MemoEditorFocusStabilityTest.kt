package com.lomo.app.feature.memo

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEditorController focus-token stability during long-form mode toggles.
 * - Behavior focus: expanding and collapsing the current editor session must not request a new editor focus cycle, because the existing keyboard session should stay intact while the sheet morphs between compact and long-form.
 * - Observable outcomes: focusRequestToken remains unchanged across expand and collapse on an already-visible session.
 * - Red phase: Fails before the fix because expand() and collapse() both bump focusRequestToken, which re-triggers InputSheet focus effects and causes the keyboard layout to flicker during long-form transitions.
 * - Excludes: Compose rendering, Android IME OEM behavior, and memo submission flow.
 */
class MemoEditorFocusStabilityTest {
    @Test
    fun `expanding and collapsing current session keeps the existing focus request token`() {
        val controller = MemoEditorController()

        controller.openForCreate("draft")
        val initialFocusRequestToken = controller.focusRequestToken

        controller.expand()
        assertEquals(initialFocusRequestToken, controller.focusRequestToken)

        controller.collapse()
        assertEquals(initialFocusRequestToken, controller.focusRequestToken)
    }
}
