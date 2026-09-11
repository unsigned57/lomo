package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: memo-card body transition mode policy functions.
 * - Owning layer: ui-components.
 * - Priority tier: P0 (crash fix).
 * - Capability: the card body transition path must not create subcomposition
 *   (SubcomposeLayout / AnimatedContent) inside LazyColumn items, which conflicts with
 *   LazyList prefetch reuse groups.
 *
 * Scenarios:
 * - Given shouldShowExpand = true, when resolving transition mode, then
 *   StateContentTransform is selected so the expandable card resolves its collapsed
 *   target preview mode without needing AnimatedContent's SubcomposeLayout.
 * - Given shouldShowExpand = false, when resolving transition mode, then Snap is
 *   selected for non-expandable cards.
 *
 * Observable outcomes:
 * - resolveMemoCardBodyTransitionMode maps boolean to the correct mode.
 *
 * TDD proof:
 * - Fails before the fix because expandable card body transitions were selected inline by
 *   Compose rendering and could still route through AnimatedContent/SubcomposeLayout in
 *   lazy-list item slots.
 *
 * Excludes:
 * - Compose rendering, animation execution, LazyList prefetch behavior.
 */
class MemoCardBodyTransitionModeTest : UiComponentsFunSpec() {
    init {
        test("given shouldShowExpand is true when resolving transition mode then StateContentTransform is used") {
            resolveMemoCardBodyTransitionMode(shouldShowExpand = true) shouldBe
                MemoCardBodyTransitionMode.StateContentTransform
        }

        test("given shouldShowExpand is false when resolving transition mode then Snap is used") {
            resolveMemoCardBodyTransitionMode(shouldShowExpand = false) shouldBe
                MemoCardBodyTransitionMode.Snap
        }
    }
}
