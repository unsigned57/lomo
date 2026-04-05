package com.lomo.app.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
class NewMemoInsertAnimationSessionTest {
    @Test
    fun `arm waits for a different top memo id and then starts blank-space preparation before reveal`() {
        val session = NewMemoInsertAnimationSession()

        assertTrue(session.arm(previousTopMemoId = "memo-01"))
        assertTrue(session.state.awaitingInsertedTopMemo)
        assertNull(session.state.blankSpaceMemoId)
        assertNull(session.state.gapReadyMemoId)
        assertNull(session.state.pendingRevealMemoId)

        assertNull(session.markInsertedTopMemoReady(insertedTopMemoId = "memo-01"))
        assertTrue(session.state.awaitingInsertedTopMemo)
        assertNull(session.state.blankSpaceMemoId)
        assertNull(session.state.gapReadyMemoId)
        assertNull(session.state.pendingRevealMemoId)

        assertEquals("memo-new", session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new"))
        assertFalse(session.state.awaitingInsertedTopMemo)
        assertEquals("memo-new", session.state.blankSpaceMemoId)
        assertNull(session.state.gapReadyMemoId)
        assertNull(session.state.pendingRevealMemoId)
    }

    @Test
    fun `arm treats the first non-null top memo as the blank-space target when the list was previously empty`() {
        val session = NewMemoInsertAnimationSession()

        assertTrue(session.arm(previousTopMemoId = null))

        assertNull(session.markInsertedTopMemoReady(insertedTopMemoId = null))
        assertEquals("memo-new", session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new"))
        assertEquals(
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                blankSpaceMemoId = "memo-new",
            ),
            session.state,
        )
    }

    @Test
    fun `markBlankSpacePrepared moves the active memo into gap-ready state only`() {
        val session =
            NewMemoInsertAnimationSession(
                initialState =
                    NewMemoInsertAnimationState(
                        awaitingInsertedTopMemo = false,
                        blankSpaceMemoId = "memo-new",
                    ),
            )

        session.markBlankSpacePrepared(memoId = "other")
        assertEquals("memo-new", session.state.blankSpaceMemoId)
        assertNull(session.state.gapReadyMemoId)
        assertNull(session.state.pendingRevealMemoId)

        session.markBlankSpacePrepared(memoId = "memo-new")
        assertEquals(
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                gapReadyMemoId = "memo-new",
            ),
            session.state,
        )
    }

    @Test
    fun `markRevealReady promotes only the gap-ready memo into reveal stage`() {
        val session =
            NewMemoInsertAnimationSession(
                initialState =
                    NewMemoInsertAnimationState(
                        awaitingInsertedTopMemo = false,
                        gapReadyMemoId = "memo-new",
                    ),
            )

        session.markRevealReady(memoId = "other")
        assertEquals("memo-new", session.state.gapReadyMemoId)
        assertNull(session.state.pendingRevealMemoId)

        session.markRevealReady(memoId = "memo-new")
        assertEquals(
            NewMemoInsertAnimationState(
                awaitingInsertedTopMemo = false,
                pendingRevealMemoId = "memo-new",
            ),
            session.state,
        )
    }

    @Test
    fun `clearReveal resets the session only for the active reveal target`() {
        val session =
            NewMemoInsertAnimationSession(
                initialState =
                    NewMemoInsertAnimationState(
                        awaitingInsertedTopMemo = false,
                        pendingRevealMemoId = "memo-new",
                    ),
            )

        session.clearReveal(memoId = "other")
        assertEquals("memo-new", session.state.pendingRevealMemoId)

        session.clearReveal(memoId = "memo-new")
        assertEquals(NewMemoInsertAnimationState(), session.state)
    }

    @Test
    fun `arm rejects overlap while waiting for insertion or reveal completion`() {
        val session = NewMemoInsertAnimationSession()

        assertTrue(session.arm(previousTopMemoId = "memo-01"))
        assertFalse(session.arm(previousTopMemoId = "memo-02"))

        assertEquals("memo-new", session.markInsertedTopMemoReady(insertedTopMemoId = "memo-new"))
        assertFalse(session.arm(previousTopMemoId = "memo-03"))

        session.markBlankSpacePrepared(memoId = "memo-new")
        assertFalse(session.arm(previousTopMemoId = "memo-04"))

        session.markRevealReady(memoId = "memo-new")
        assertFalse(session.arm(previousTopMemoId = "memo-04"))
    }
}
