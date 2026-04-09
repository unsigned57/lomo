package com.lomo.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo paragraph interaction policy in the ui-components layer.
 * - Behavior focus: paragraphs that are both selectable and link-bearing must preserve platform
 *   text selection while also enabling short-tap URL activation instead of forcing callers to
 *   choose one interaction over the other.
 * - Observable outcomes: resolved movement-method policy and manual-link-tap handling flag for
 *   selectable linked, selectable plain, and non-selectable linked paragraphs.
 * - Red phase: Fails before the fix because selectable linked paragraphs do not resolve to any
 *   explicit link-tap handling branch, so free-text-copy mode loses URL activation.
 * - Excludes: TextView gesture dispatch internals, actual Activity launch behavior, and OEM action-mode UI.
 */
class MemoParagraphInteractionPolicyTest {
    @Test
    fun `selectable linked paragraphs preserve selection and enable manual link taps`() {
        val policy =
            resolveMemoParagraphInteractionPolicy(
                hasLinks = true,
                selectable = true,
            )

        assertEquals(MemoParagraphMovementMethodPolicy.PreserveExisting, policy.movementMethodPolicy)
        assertTrue(policy.enableManualLinkTapHandling)
    }

    @Test
    fun `selectable plain paragraphs keep selection without manual link taps`() {
        val policy =
            resolveMemoParagraphInteractionPolicy(
                hasLinks = false,
                selectable = true,
            )

        assertEquals(MemoParagraphMovementMethodPolicy.PreserveExisting, policy.movementMethodPolicy)
        assertEquals(false, policy.enableManualLinkTapHandling)
    }

    @Test
    fun `non selectable linked paragraphs still use link movement method`() {
        val policy =
            resolveMemoParagraphInteractionPolicy(
                hasLinks = true,
                selectable = false,
            )

        assertEquals(MemoParagraphMovementMethodPolicy.LinkOnly, policy.movementMethodPolicy)
        assertEquals(false, policy.enableManualLinkTapHandling)
    }
}
