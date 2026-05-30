package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: NewMemoInsertAnimationSession
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: stage a new memo's insertion (reserve the top slot hidden, detect the inserted first memo, open
 *   blank space, wait for an explicit reveal-ready handoff, then reveal and reset) and never permanently block a
 *   future insertion when one staging run is abandoned.
 *
 * Scenarios:
 * - Given an armed session, when the same top id reappears it keeps awaiting; when a different top id appears it
 *   moves to blank-space with that id.
 * - Given a previously empty list, when the first non-null top id appears, then it becomes the blank-space target.
 * - Given a blank-space target, when blank space is prepared for it, then it advances to gap-ready (ignoring other ids).
 * - Given a gap-ready target, when reveal is marked for it, then it advances to pending-reveal (ignoring other ids).
 * - Given a pending-reveal target, when reveal is cleared for it, then the session resets (ignoring other ids).
 * - Given a session still mid-flight, when armed again for a new creation, then it supersedes the stale run with a
 *   fresh awaiting state instead of refusing.
 *
 * Observable outcomes:
 * - The session.state transitions and the return of markInsertedTopMemoReady.
 *
 * TDD proof:
 * - RED: against the prior contract arm() returned false and kept the stale state while a run was in flight, so the
 *   supersede scenario failed (state stayed on the first creation). An abandoned run therefore blocked every later
 *   insertion and disabled placement springs - the reported "sometimes no animation, memo just appears" case.
 * - RED command: `./gradlew :app:testDebugUnitTest --tests 'com.lomo.app.feature.main.NewMemoInsertAnimationSessionTest'`.
 * - GREEN: arm() always resets to a fresh awaiting state for the new creation.
 *
 * Excludes:
 * - LazyColumn measurement, Compose animation frames, repository persistence, and scroll physics.
 *
 * Test Change Justification:
 * - Reason category: production contract change.
 * - Old behavior/assertion being replaced: "arm rejects overlap while waiting" asserted arm() returns false mid-flight.
 * - Why old assertion is no longer correct: a staging run can be abandoned (paging refresh / disposed row), and a
 *   refusing arm() then permanently blocks new-memo animation; a new creation must supersede the stale run.
 * - Coverage preserved by: the supersede scenario still locks the staged transitions; only the refusal is replaced.
 * - Why this is not fitting the test to the implementation: the assertion encodes the intended "new creation wins"
 *   behavior that fixes the reported no-animation case, not an internal detail.
 */
class NewMemoInsertAnimationSessionTest : AppFunSpec() {
    init {
        test("arm waits for a different top memo id and then starts blank-space preparation before reveal") {
            val session = NewMemoInsertAnimationSession()

            session.arm(previousTopMemoId = "memo-01")
            session.state.awaitingInsertedTopMemo shouldBe true
            session.state.blankSpaceMemoId shouldBe null
            session.state.gapReadyMemoId shouldBe null
            session.state.pendingRevealMemoId shouldBe null

            session.markInsertedTopMemoReady(insertedTopMemoId = "memo-01") shouldBe null
            session.state.awaitingInsertedTopMemo shouldBe true
            session.state.blankSpaceMemoId shouldBe null

            session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new") shouldBe "memo-new"
            session.state.awaitingInsertedTopMemo shouldBe false
            session.state.blankSpaceMemoId shouldBe "memo-new"
            session.state.gapReadyMemoId shouldBe null
            session.state.pendingRevealMemoId shouldBe null
        }

        test("arm treats the first non-null top memo as the blank-space target when the list was previously empty") {
            val session = NewMemoInsertAnimationSession()

            session.arm(previousTopMemoId = null)

            session.markInsertedTopMemoReady(insertedTopMemoId = null) shouldBe null
            session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new") shouldBe "memo-new"
            session.state shouldBe NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                blankSpaceMemoId = "memo-new",
            )
        }

        test("markBlankSpacePrepared moves the active memo into gap-ready state only") {
            val session = NewMemoInsertAnimationSession(
                initialState = NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    blankSpaceMemoId = "memo-new",
                ),
            )

            session.markBlankSpacePrepared(memoId = "other")
            session.state.blankSpaceMemoId shouldBe "memo-new"
            session.state.gapReadyMemoId shouldBe null

            session.markBlankSpacePrepared(memoId = "memo-new")
            session.state shouldBe NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                gapReadyMemoId = "memo-new",
            )
        }

        test("markRevealReady promotes only the gap-ready memo into reveal stage") {
            val session = NewMemoInsertAnimationSession(
                initialState = NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    gapReadyMemoId = "memo-new",
                ),
            )

            session.markRevealReady(memoId = "other")
            session.state.gapReadyMemoId shouldBe "memo-new"
            session.state.pendingRevealMemoId shouldBe null

            session.markRevealReady(memoId = "memo-new")
            session.state shouldBe NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                pendingRevealMemoId = "memo-new",
            )
        }

        test("clearReveal resets the session only for the active reveal target") {
            val session = NewMemoInsertAnimationSession(
                initialState = NewMemoInsertAnimationState(
                    awaitingInsertedTopMemo = false,
                    pendingRevealMemoId = "memo-new",
                ),
            )

            session.clearReveal(memoId = "other")
            session.state.pendingRevealMemoId shouldBe "memo-new"

            session.clearReveal(memoId = "memo-new")
            session.state shouldBe NewMemoInsertAnimationState()
        }

        test("arm supersedes a stale in-flight run so a dropped staging never blocks new insertions") {
            val session = NewMemoInsertAnimationSession()

            session.arm(previousTopMemoId = "memo-01")
            // A second creation while still awaiting supersedes the first with a fresh awaiting state.
            session.arm(previousTopMemoId = "memo-02")
            session.state shouldBe NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-02",
            )

            // Even after the run advanced to blank-space, a new creation re-arms fresh.
            session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new") shouldBe "memo-new"
            session.arm(previousTopMemoId = "memo-03")
            session.state shouldBe NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = true,
                previousTopMemoId = "memo-03",
            )
        }
    }
}
