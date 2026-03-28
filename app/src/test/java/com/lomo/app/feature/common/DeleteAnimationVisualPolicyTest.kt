package com.lomo.app.feature.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: resolveDeleteAnimationVisualPolicy
 * - Behavior focus: visual policy for deleting rows, specifically whether a row still participates in placement animation and whether it keeps a stable alpha layer during delete.
 * - Observable outcomes: animatePlacement and keepStableAlphaLayer flags for deleting vs non-deleting rows.
 * - Red phase: Fails before the fix because the delete animation path has no extracted policy to disable placement motion and keep a stable alpha layer, allowing row-delete flicker to persist.
 * - Excludes: Compose runtime rendering, frame-by-frame GPU output, and ViewModel mutation orchestration.
 */
class DeleteAnimationVisualPolicyTest {
    @Test
    fun `deleting rows disable placement motion and keep a stable alpha layer`() {
        val policy = resolveDeleteAnimationVisualPolicy(isDeleting = true)

        assertFalse(policy.animatePlacement)
        assertTrue(policy.keepStableAlphaLayer)
    }

    @Test
    fun `idle rows keep placement motion and skip the extra alpha layer`() {
        val policy = resolveDeleteAnimationVisualPolicy(isDeleting = false)

        assertTrue(policy.animatePlacement)
        assertFalse(policy.keepStableAlphaLayer)
    }
}
