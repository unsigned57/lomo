package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

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
class MemoCardListPlacementAnimationPolicyTest : AppFunSpec() {
    init {
        test("placement lists disable lazy enter fade after initial entrance settles") {
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

            ((active.usesLazyItemFadeIn)) shouldBe true
            ((settled.usesLazyItemFadeIn)) shouldBe false
            ((settled.usesPlacementSpring)) shouldBe true
        }
    }

    init {
        test("memo expansion blocks shared placement spring while card height owns movement") {
            val policy =
                resolveMemoCardListItemMotionPolicy(
                    animation = MemoCardListAnimation.Placement,
                    entranceState = MemoCardListEntranceState.Settled,
                    deleteAnimationEnabled = false,
                    blockPlacementSpringForDeleteViewportEntry = false,
                    blockPlacementSpringForMemoExpansion = true,
                )

            ((policy.usesLazyItemFadeIn)) shouldBe false
            ((policy.usesPlacementSpring)) shouldBe false
        }
    }

    init {
        test("non placement lists keep delete placement without lazy enter fade") {
            val policy =
                resolveMemoCardListItemMotionPolicy(
                    animation = MemoCardListAnimation.None,
                    entranceState = MemoCardListEntranceState.Settled,
                    deleteAnimationEnabled = true,
                    blockPlacementSpringForDeleteViewportEntry = false,
                    blockPlacementSpringForMemoExpansion = false,
                )

            ((policy.usesLazyItemFadeIn)) shouldBe false
            ((policy.usesPlacementSpring)) shouldBe true
        }
    }

}
