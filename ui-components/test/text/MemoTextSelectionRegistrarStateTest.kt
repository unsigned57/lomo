package com.lomo.ui.text

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoTextSelectionRegistrar.hasSelection — the read-only flag callers
 *   use to drive cross-paragraph dismissal logic.
 *
 * Scenarios:
 * - Happy: registrar reports no selection before beginSelection.
 * - Boundary: registrar reports active selection synchronously after beginSelection.
 * - Failure: registrar reports no selection synchronously after clear.
 * - Must-not-happen: selection flag never stays stale after synchronous modification.
 *
 * Behavior focus: the flag must reflect the *current* selection state synchronously after
 *   `beginSelection` and `clear`, so a tap handler in any paragraph block can read the live
 *   value at invocation time and decide whether the scope-level selection should be cleared.
 *   Stale reads break the "tap any paragraph cancels the selection" UX on expanded memos.
 * - Observable outcomes: registrar.hasSelection before any selection, after beginSelection,
 *   and after clear.
 * - TDD proof: Fails before the fix because previous implementations exposed the scope
 *   state only through a lambda whose closure could capture a stale `scope` reference if
 *   the paragraph's pointerInput suspend block didn't restart on every recomposition.
 * - Excludes: drag dispatch, paragraph block registration timing, popup positioning.
 */
class MemoTextSelectionRegistrarStateTest : UiComponentsFunSpec() {
    init {
        test("registrar reports no selection before beginSelection is called") {
            val registrar = MemoTextSelectionRegistrar()

            registrar.hasSelection shouldBe false
        }

        test("registrar reports an active selection synchronously after beginSelection") {
            val registrar = MemoTextSelectionRegistrar()

            registrar.beginSelection(blockKey = "block", range = 3..8)

            registrar.hasSelection shouldBe true
        }

        test("registrar reports no selection synchronously after clear") {
            val registrar = MemoTextSelectionRegistrar()
            registrar.beginSelection(blockKey = "block", range = 0..2)
            registrar.hasSelection shouldBe true

            registrar.clear()

            registrar.hasSelection shouldBe false
        }
    }
}
