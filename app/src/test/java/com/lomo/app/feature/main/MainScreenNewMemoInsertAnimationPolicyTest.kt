package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: isInsertedTopMemoReadyForSpaceStage (main-screen new-memo insert advancement policy)
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: decide when an armed insertion can open its blank-space stage, based solely on a new top memo id
 *   appearing - the viewport re-pin after a prepend is performed by the effect, not gated here.
 *
 * Scenarios:
 * - Given an armed session whose current top still equals the previous top, when checked, then not ready.
 * - Given the insertion wait already cleared (not awaiting), when checked, then not ready.
 * - Given an armed session and a different top memo id, when checked, then ready (even though the prepend left the
 *   viewport anchored on the previous memo).
 * - Given an armed session over a previously empty list and the first top memo id, when checked, then ready.
 *
 * Observable outcomes:
 * - The boolean readiness decision.
 *
 * TDD proof:
 * - RED: the prior policy took an isListPinnedAtTop gate and returned false while the list was not pinned; after a
 *   paging-refresh prepend re-anchors the list on the previous top, that gate is never satisfied, so the staged
 *   animation stalled and the new row stayed in the off-screen top (reported "memo appears in the invisible top area").
 *   Calling the policy without the pin gate fails to compile against the old three-argument signature.
 * - RED command: `./gradlew :app:testDebugUnitTest --tests 'com.lomo.app.feature.main.MainScreenNewMemoInsertAnimationPolicyTest'`.
 * - GREEN: readiness depends only on awaiting + a new top memo id; the effect scrolls to the absolute top before advancing.
 *
 * Excludes:
 * - LazyColumn measurement, animation frames, repository persistence, and gesture dispatch.
 *
 * Test Change Justification:
 * - Reason category: production contract change.
 * - Old behavior/assertion being replaced: readiness required isListPinnedAtTop == true.
 * - Why old assertion is no longer correct: the prepend re-anchors the viewport away from the absolute top, so a pin
 *   gate blocks the gap stage exactly when the new row is still hidden; the effect re-pins on detection instead.
 * - Coverage preserved by: still locking same-top and not-awaiting rejection plus new-top readiness.
 * - Why this is not fitting the test to the implementation: the assertions encode the reported off-screen-stall fix
 *   (advance on new-top, re-pin in the effect) rather than an internal detail.
 */
class MainScreenNewMemoInsertAnimationPolicyTest : AppFunSpec() {
    init {
        test("not ready before a different first memo is inserted") {
            val state = NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-00",
            )

            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-00",
            ) shouldBe false
        }

        test("not ready after the insertion wait has already cleared") {
            val state = NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                blankSpaceMemoId = "memo-new",
            )

            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-new",
            ) shouldBe false
        }

        test("ready once the inserted memo becomes list top even though the prepend left the viewport unpinned") {
            val state = NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-00",
            )

            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-new",
            ) shouldBe true
        }

        test("ready when the first memo appears over a previously empty list") {
            val state = NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = null,
            )

            isInsertedTopMemoReadyForSpaceStage(
                state = state,
                currentListTopMemoId = "memo-new",
            ) shouldBe true
        }
    }
}
