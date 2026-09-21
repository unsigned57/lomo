package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: main-screen app-action consumption policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: decide which pending app actions remain queued after one handling attempt.
 *
 * Scenarios:
 * - Given a focus action is still waiting for paging, when handling completes, then the action remains pending.
 * - Given a focus action placed the target, when handling completes, then the action is consumed.
 * - Given a focus action found a missing identity, when handling completes, then the action is consumed.
 * - Given an open action is handled once, when handling completes, then the action is consumed.
 *
 * Observable outcomes: boolean consume/retain decision.
 *
 * TDD proof: Fails if WaitingForPage is consumed or Missing is retained as if it were still pending.
 *
 * Excludes: Compose LaunchedEffect scheduling, NavHost back-stack transitions, and LazyListState scroll physics.
 *
 * Test Change Justification:
 * - Reason category: systemic behavior replacement.
 * - Old behavior/assertion being replaced: consume-on-boolean-handled, which treated offscreen
 *   placement as a retain signal identical to a missing memo.
 * - Why old assertion is no longer correct: Missing is terminal; WaitingForPage must retry.
 * - Coverage preserved by: Placed consume and OpenMemo consume remain covered.
 * - Why this is not fitting the test to the implementation: the test asserts the public consume policy decision.
 */
class MainScreenAppActionConsumptionPolicyTest : AppFunSpec() {
    init {
        test("focus memo is retained while paging has not exposed the target") {
            shouldConsumeAppActionAfterHandling(
                action = MainViewModel.AppAction.FocusMemo("memo-42"),
                attempt = MainScreenFocusAttempt.WaitingForPage,
            ) shouldBe false
        }

        test("focus memo is consumed after successful placement") {
            shouldConsumeAppActionAfterHandling(
                action = MainViewModel.AppAction.FocusMemo("memo-42"),
                attempt = MainScreenFocusAttempt.Placed,
            ) shouldBe true
        }

        test("focus memo is consumed when the identity is missing") {
            shouldConsumeAppActionAfterHandling(
                action = MainViewModel.AppAction.FocusMemo("memo-42"),
                attempt = MainScreenFocusAttempt.Missing,
            ) shouldBe true
        }

        test("open action is consumed after one handling attempt") {
            shouldConsumeAppActionAfterHandling(
                action = MainViewModel.AppAction.OpenMemo("memo-42"),
                attempt = null,
            ) shouldBe true
        }
    }
}
