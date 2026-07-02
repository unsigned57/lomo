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
 * - Given a focus action cannot locate its target, when handling completes, then the action remains pending.
 * - Given a focus action locates its target, when handling completes, then the action is consumed.
 * - Given create, start-recording, or open actions are handled once, when handling completes, then the actions are consumed.
 *
 * Observable outcomes: boolean consume/retain decision.
 *
 * TDD proof: Fails before the fix because HandleAppActionEvents consumes FocusMemo immediately even when focusMemoInList returns false.
 *
 * Excludes: Compose LaunchedEffect scheduling, NavHost back-stack transitions, and LazyListState scroll physics.
 *
 * Test Change Justification:
 * - Reason category: entry contract extension.
 * - Old behavior/assertion being replaced: one-shot app actions only covered create and open actions.
 * - Why old assertion is no longer correct: start recording is now an external app action with the same one-shot consume contract.
 * - Coverage preserved by: focus retain/consume and create/open consume scenarios remain covered.
 * - Why this is not fitting the test to the implementation: the test asserts the public consume policy decision.
 */
class MainScreenAppActionConsumptionPolicyTest : AppFunSpec() {
    init {
        test("focus memo is retained when target cannot be focused yet") {
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.FocusMemo("memo-42"),
                    handled = false,
                )) shouldBe (false)
        }
    }

    init {
        test("focus memo is consumed after successful focus") {
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.FocusMemo("memo-42"),
                    handled = true,
                )) shouldBe (true)
        }

        test("create start-recording and open actions are consumed after one handling attempt") {
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.CreateMemo,
                    handled = false,
                )) shouldBe (true)
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.StartRecording,
                    handled = false,
                )) shouldBe (true)
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.OpenMemo("memo-42"),
                    handled = false,
                )) shouldBe (true)
        }
    }
}
