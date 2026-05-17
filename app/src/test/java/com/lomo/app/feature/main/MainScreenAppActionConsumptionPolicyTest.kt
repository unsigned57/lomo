package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: main-screen app-action consumption policy.
 * - Behavior focus: FocusMemo jump events must remain pending when the main list cannot yet locate the target, then be consumed only after a successful focus.
 * - Observable outcomes: boolean consume/retain decision for create, open, successful focus, and failed focus actions.
 * - Red phase: Fails before the fix because HandleAppActionEvents consumes FocusMemo immediately even when focusMemoInList returns false.
 * - Excludes: Compose LaunchedEffect scheduling, NavHost back-stack transitions, and LazyListState scroll physics.
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
    }

    init {
        test("create and open actions are consumed after one handling attempt") {
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.CreateMemo,
                    handled = false,
                )) shouldBe (true)
            (shouldConsumeAppActionAfterHandling(
                    action = MainViewModel.AppAction.OpenMemo("memo-42"),
                    handled = false,
                )) shouldBe (true)
        }
    }

}
