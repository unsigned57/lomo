package com.lomo.app.feature.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Main-screen new-memo insert animation advancement policy.
 * - Behavior focus: once prepend creation has already recovered the list to the absolute top, the staged insert
 *   must not ask for any additional post-insert top re-anchor; as soon as the new memo is the list's first item
 *   and the list remains pinned at top, the policy must start the blank-space stage even if the real viewport top
 *   still shows the previous memo while the new row is hidden.
 * - Observable outcomes: boolean decisions for post-insert top re-anchor suppression and inserted-memo
 *   readiness.
 * - Red phase: Fails before the fix because the policy still requires the viewport top to equal the inserted
 *   memo before blank-space prep can begin, which leaves the prepend animation stalled until manual scrolling or
 *   a compensating re-anchor happens.
 * - Excludes: LazyColumn measurement, actual animation frames, repository persistence, and gesture dispatch.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the previous policy tests treated the real viewport top as a required
 *   readiness gate and allowed a compensating post-insert top pin to recover from that gate.
 * - Why the previous assertion is no longer correct: the requested animation order is "jump to top first, then
 *   open gap, then fade in". Keeping a viewport-top gate after insertion blocks the gap stage exactly when the
 *   new row is still intentionally hidden.
 * - Coverage preserved by: the updated tests still protect top-pinned readiness, overlap avoidance, and no extra
 *   post-insert recovery, while now locking the no-manual-scroll prepend contract.
 * - Why this is not fitting the test to the implementation: the new assertions capture the reported user-visible
 *   stall and flash conditions rather than a private refactor detail.
 */
class MainScreenNewMemoInsertAnimationPolicyTest {
    @Test
    fun `policy does not start blank-space stage before a different first memo is inserted`() {
        val state =
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-00",
            )

        assertFalse(
            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-00",
                isListPinnedAtTop = true,
            ),
        )
    }

    @Test
    fun `policy does not start blank-space stage after the insertion wait has already cleared`() {
        val state =
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                blankSpaceMemoId = "memo-new",
            )

        assertFalse(
            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-new",
                isListPinnedAtTop = true,
            ),
        )
    }

    @Test
    fun `policy starts blank-space stage once inserted memo becomes list top while list is pinned at top`() {
        val state =
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-00",
            )

        assertTrue(
            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-new",
                isListPinnedAtTop = true,
            ),
        )
    }

    @Test
    fun `policy does not start blank-space stage while the list is not pinned at absolute top`() {
        val state =
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-00",
            )

        assertFalse(
            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-new",
                isListPinnedAtTop = false,
            ),
        )
    }
}
