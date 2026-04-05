package com.lomo.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo paragraph movement-method policy in the ui-components layer.
 * - Behavior focus: free-text-copy mode must preserve the platform selection movement method that
 *   `setTextIsSelectable(true)` installs, while non-selectable linked text must still opt into
 *   link handling and plain non-selectable text must not keep an unnecessary movement method.
 * - Observable outcomes: selected movement-method policy for selectable, linked, and plain text.
 * - Red phase: Fails before the fix because selectable memo paragraphs still resolve to the
 *   branch that clears or overrides the movement method, which removes the platform long-press
 *   selection behavior on rendered memo text.
 * - Excludes: TextView framework internals, selection handle visuals, and OEM-specific action mode UI.
 */
class MemoParagraphMovementMethodPolicyTest {
    @Test
    fun `free copy preserves platform movement method even when markdown contains links`() {
        val policy =
            resolveMemoParagraphMovementMethodPolicy(
                hasLinks = true,
                selectable = true,
            )

        assertEquals(MemoParagraphMovementMethodPolicy.PreserveExisting, policy)
    }

    @Test
    fun `linked non selectable text installs link movement method`() {
        val policy =
            resolveMemoParagraphMovementMethodPolicy(
                hasLinks = true,
                selectable = false,
            )

        assertEquals(MemoParagraphMovementMethodPolicy.LinkOnly, policy)
    }

    @Test
    fun `plain non selectable text clears movement method`() {
        val policy =
            resolveMemoParagraphMovementMethodPolicy(
                hasLinks = false,
                selectable = false,
            )

        assertEquals(MemoParagraphMovementMethodPolicy.None, policy)
    }
}
