package com.lomo.app.feature.memo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoCardList item motion policy.
 * - Behavior focus: shared non-main memo lists must not keep LazyColumn enter animations armed
 *   after the first screen-level entrance has settled, and memo expand/collapse must own row
 *   movement while card height is changing.
 * - Observable outcomes: resolved lazy fade-in and placement-spring policy booleans.
 * - Red phase: Fails before the fix because MemoCardList wires a permanent animateItem
 *   fadeInSpec/placementSpec for Placement lists and has no shared expansion movement blocker.
 * - Excludes: runtime Compose frame timing, exact easing curves, and pixel-level scroll positions.
 */
class MemoCardListPlacementAnimationPolicyTest {
    @Test
    fun `placement lists disable lazy enter fade after initial entrance settles`() {
        val active =
            resolveMemoCardListItemMotionPolicy(
                animation = MemoCardListAnimation.Placement,
                entranceState = MemoCardListEntranceState.Active,
                deleteAnimationEnabled = false,
                blockPlacementSpringForDeleteViewportEntry = false,
                blockPlacementSpringForMemoExpansion = false,
            )
        val settled =
            resolveMemoCardListItemMotionPolicy(
                animation = MemoCardListAnimation.Placement,
                entranceState = MemoCardListEntranceState.Settled,
                deleteAnimationEnabled = false,
                blockPlacementSpringForDeleteViewportEntry = false,
                blockPlacementSpringForMemoExpansion = false,
            )

        assertTrue(active.usesLazyItemFadeIn)
        assertFalse(settled.usesLazyItemFadeIn)
        assertTrue(settled.usesPlacementSpring)
    }

    @Test
    fun `memo expansion blocks shared placement spring while card height owns movement`() {
        val policy =
            resolveMemoCardListItemMotionPolicy(
                animation = MemoCardListAnimation.Placement,
                entranceState = MemoCardListEntranceState.Settled,
                deleteAnimationEnabled = false,
                blockPlacementSpringForDeleteViewportEntry = false,
                blockPlacementSpringForMemoExpansion = true,
            )

        assertFalse(policy.usesLazyItemFadeIn)
        assertFalse(policy.usesPlacementSpring)
    }

    @Test
    fun `non placement lists keep delete placement without lazy enter fade`() {
        val policy =
            resolveMemoCardListItemMotionPolicy(
                animation = MemoCardListAnimation.None,
                entranceState = MemoCardListEntranceState.Settled,
                deleteAnimationEnabled = true,
                blockPlacementSpringForDeleteViewportEntry = false,
                blockPlacementSpringForMemoExpansion = false,
            )

        assertFalse(policy.usesLazyItemFadeIn)
        assertTrue(policy.usesPlacementSpring)
    }
}
