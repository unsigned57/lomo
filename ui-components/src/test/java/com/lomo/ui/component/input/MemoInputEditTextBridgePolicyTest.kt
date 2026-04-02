package com.lomo.ui.component.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo input presentation replacement policy.
 * - Behavior focus: the editor must keep the active buffer when recomposition does not change text or paragraph spacing, but must still replace content when text or paragraph gap styling changes.
 * - Observable outcomes: boolean replacement decision for stable-text, changed-text, and changed-spacing cases.
 * - Red phase: Fails before the fix because identical-text recompositions still route through full content replacement instead of preserving the active editor buffer.
 * - Excludes: Android EditText widget internals, IME rendering, selection synchronization, and Compose hosting.
 */
class MemoInputEditTextBridgePolicyTest {
    @Test
    fun `same text and same spacing keeps the current editable buffer`() {
        val shouldReplace =
            shouldReplaceMemoInputPresentationText(
                currentText = "memo",
                desiredText = "memo",
                lastAppliedParagraphSpacingPx = 24,
                desiredParagraphSpacingPx = 24,
            )

        assertFalse(shouldReplace)
    }

    @Test
    fun `changed text replaces the current editable buffer`() {
        val shouldReplace =
            shouldReplaceMemoInputPresentationText(
                currentText = "memo",
                desiredText = "memo updated",
                lastAppliedParagraphSpacingPx = 24,
                desiredParagraphSpacingPx = 24,
            )

        assertTrue(shouldReplace)
    }

    @Test
    fun `changed paragraph spacing replaces the current editable buffer`() {
        val shouldReplace =
            shouldReplaceMemoInputPresentationText(
                currentText = "memo",
                desiredText = "memo",
                lastAppliedParagraphSpacingPx = 24,
                desiredParagraphSpacingPx = 28,
            )

        assertTrue(shouldReplace)
    }
}
