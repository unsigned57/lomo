package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: NewMemoInsertAnimationSession
 * - Behavior focus: a newly created memo should keep the top slot reserved and hidden until the inserted first
 *   memo is detected, then open blank space, wait for an explicit reveal-ready handoff, and only after that
 *   reveal the new memo and clear the session.
 * - Observable outcomes: arming acceptance, awaiting-insert state, detected blank-space target id, deferred
 *   gap-ready state, deferred reveal target id, and session reset.
 * - Red phase: Fails before the fix because MainScreen has no insertion-session state that can suppress the new
 *   first row until the inserted memo is pinned to the visible top and explicitly revealed.
 * - Excludes: LazyColumn measurement, actual Compose animation frames, repository persistence, and scroll physics.
 */
class NewMemoInsertAnimationSessionTest : AppFunSpec() {
    init {
        test("arm waits for a different top memo id and then starts blank-space preparation before reveal") {
            val session = NewMemoInsertAnimationSession()

            ((session.arm(previousTopMemoId = "memo-01"))) shouldBe true
            ((session.state.awaitingInsertedTopMemo)) shouldBe true
            (session.state.blankSpaceMemoId) shouldBe null
            (session.state.gapReadyMemoId) shouldBe null
            (session.state.pendingRevealMemoId) shouldBe null

            (session.markInsertedTopMemoReady(insertedTopMemoId = "memo-01")) shouldBe null
            ((session.state.awaitingInsertedTopMemo)) shouldBe true
            (session.state.blankSpaceMemoId) shouldBe null
            (session.state.gapReadyMemoId) shouldBe null
            (session.state.pendingRevealMemoId) shouldBe null

            (session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new")) shouldBe ("memo-new")
            ((session.state.awaitingInsertedTopMemo)) shouldBe false
            (session.state.blankSpaceMemoId) shouldBe ("memo-new")
            (session.state.gapReadyMemoId) shouldBe null
            (session.state.pendingRevealMemoId) shouldBe null
        }
    }

    init {
        test("arm treats the first non-null top memo as the blank-space target when the list was previously empty") {
            val session = NewMemoInsertAnimationSession()

            ((session.arm(previousTopMemoId = null))) shouldBe true

            (session.markInsertedTopMemoReady(insertedTopMemoId = null)) shouldBe null
            (session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new")) shouldBe ("memo-new")
            (session.state) shouldBe (NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    blankSpaceMemoId = "memo-new",
                ))
        }
    }

    init {
        test("markBlankSpacePrepared moves the active memo into gap-ready state only") {
            val session =
                NewMemoInsertAnimationSession(
                    initialState =
                        NewMemoInsertAnimationState(
                            awaitingInsertedTopMemo = false,
                            blankSpaceMemoId = "memo-new",
                        ),
                )

            session.markBlankSpacePrepared(memoId = "other")
            (session.state.blankSpaceMemoId) shouldBe ("memo-new")
            (session.state.gapReadyMemoId) shouldBe null
            (session.state.pendingRevealMemoId) shouldBe null

            session.markBlankSpacePrepared(memoId = "memo-new")
            (session.state) shouldBe (NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    gapReadyMemoId = "memo-new",
                ))
        }
    }

    init {
        test("markRevealReady promotes only the gap-ready memo into reveal stage") {
            val session =
                NewMemoInsertAnimationSession(
                    initialState =
                        NewMemoInsertAnimationState(
                            awaitingInsertedTopMemo = false,
                            gapReadyMemoId = "memo-new",
                        ),
                )

            session.markRevealReady(memoId = "other")
            (session.state.gapReadyMemoId) shouldBe ("memo-new")
            (session.state.pendingRevealMemoId) shouldBe null

            session.markRevealReady(memoId = "memo-new")
            (session.state) shouldBe (NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    pendingRevealMemoId = "memo-new",
                ))
        }
    }

    init {
        test("clearReveal resets the session only for the active reveal target") {
            val session =
                NewMemoInsertAnimationSession(
                    initialState =
                        NewMemoInsertAnimationState(
                            awaitingInsertedTopMemo = false,
                            pendingRevealMemoId = "memo-new",
                        ),
                )

            session.clearReveal(memoId = "other")
            (session.state.pendingRevealMemoId) shouldBe ("memo-new")

            session.clearReveal(memoId = "memo-new")
            (session.state) shouldBe (NewMemoInsertAnimationState())
        }
    }

    init {
        test("arm rejects overlap while waiting for insertion or reveal completion") {
            val session = NewMemoInsertAnimationSession()

            ((session.arm(previousTopMemoId = "memo-01"))) shouldBe true
            ((session.arm(previousTopMemoId = "memo-02"))) shouldBe false

            (session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new")) shouldBe ("memo-new")
            ((session.arm(previousTopMemoId = "memo-03"))) shouldBe false

            session.markBlankSpacePrepared(memoId = "memo-new")
            ((session.arm(previousTopMemoId = "memo-04"))) shouldBe false

            session.markRevealReady(memoId = "memo-new")
            ((session.arm(previousTopMemoId = "memo-04"))) shouldBe false
        }
    }

}
