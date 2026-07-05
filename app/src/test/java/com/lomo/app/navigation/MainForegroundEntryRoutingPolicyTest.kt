package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MainForegroundEntryRoutingPolicy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: converts process foreground entries into one-shot Main-screen auto-input entries
 *   only when Main is visible at the foreground boundary.
 *
 * Scenarios:
 * - Given Main is visible, when a new foreground entry arrives, then that entry is delivered to
 *   Main and marked evaluated.
 * - Given the same foreground entry was delivered to Main, when the user navigates to another
 *   route and later returns to Main, then the entry is cleared and not replayed.
 * - Given another route is visible when a new foreground entry arrives, when the user later
 *   returns to Main, then the entry stays evaluated but undelivered.
 * - Given explicit launch work suppresses auto-input, when a foreground entry arrives on Main,
 *   then the entry is evaluated without being delivered.
 *
 * Observable outcomes:
 * - evaluatedForegroundEntryId and mainForegroundEntryId state transitions.
 *
 * TDD proof:
 * - RED: before routing state cleared delivered Main entries on non-Main routes, returning from
 *   Settings to Main replayed the old foreground entry and reopened the editor.
 *
 * Excludes:
 * - Navigation Compose runtime, keyboard rendering, and editor controller side effects.
 */
class MainForegroundEntryRoutingPolicyTest : AppFunSpec() {
    init {
        test("given main is visible when foreground entry is new then entry is delivered to main") {
            val state =
                MainForegroundEntryRoutingPolicy.resolve(
                    foregroundEntryId = 7L,
                    evaluatedForegroundEntryId = 6L,
                    currentMainForegroundEntryId = 0L,
                    currentRoute = MAIN_ROUTE,
                    mainRouteName = MAIN_ROUTE,
                    suppressForegroundAutoInput = false,
                )

            state shouldBe
                MainForegroundEntryRoutingPolicy.State(
                    evaluatedForegroundEntryId = 7L,
                    mainForegroundEntryId = 7L,
                )
        }

        test("given delivered entry when navigating away and back to main then entry is not replayed") {
            val awayState =
                MainForegroundEntryRoutingPolicy.resolve(
                    foregroundEntryId = 7L,
                    evaluatedForegroundEntryId = 7L,
                    currentMainForegroundEntryId = 7L,
                    currentRoute = SETTINGS_ROUTE,
                    mainRouteName = MAIN_ROUTE,
                    suppressForegroundAutoInput = false,
                )
            val returnedState =
                MainForegroundEntryRoutingPolicy.resolve(
                    foregroundEntryId = 7L,
                    evaluatedForegroundEntryId = awayState.evaluatedForegroundEntryId,
                    currentMainForegroundEntryId = awayState.mainForegroundEntryId,
                    currentRoute = MAIN_ROUTE,
                    mainRouteName = MAIN_ROUTE,
                    suppressForegroundAutoInput = false,
                )

            awayState shouldBe
                MainForegroundEntryRoutingPolicy.State(
                    evaluatedForegroundEntryId = 7L,
                    mainForegroundEntryId = 0L,
                )
            returnedState shouldBe
                MainForegroundEntryRoutingPolicy.State(
                    evaluatedForegroundEntryId = 7L,
                    mainForegroundEntryId = 0L,
                )
        }

        test("given non main is visible when foreground entry is new then later main return does not deliver it") {
            val foregroundOnSettingsState =
                MainForegroundEntryRoutingPolicy.resolve(
                    foregroundEntryId = 8L,
                    evaluatedForegroundEntryId = 7L,
                    currentMainForegroundEntryId = 0L,
                    currentRoute = SETTINGS_ROUTE,
                    mainRouteName = MAIN_ROUTE,
                    suppressForegroundAutoInput = false,
                )
            val returnedState =
                MainForegroundEntryRoutingPolicy.resolve(
                    foregroundEntryId = 8L,
                    evaluatedForegroundEntryId = foregroundOnSettingsState.evaluatedForegroundEntryId,
                    currentMainForegroundEntryId = foregroundOnSettingsState.mainForegroundEntryId,
                    currentRoute = MAIN_ROUTE,
                    mainRouteName = MAIN_ROUTE,
                    suppressForegroundAutoInput = false,
                )

            foregroundOnSettingsState shouldBe
                MainForegroundEntryRoutingPolicy.State(
                    evaluatedForegroundEntryId = 8L,
                    mainForegroundEntryId = 0L,
                )
            returnedState shouldBe
                MainForegroundEntryRoutingPolicy.State(
                    evaluatedForegroundEntryId = 8L,
                    mainForegroundEntryId = 0L,
                )
        }

        test("given explicit entry suppresses auto input when foreground is on main then entry is evaluated only") {
            val state =
                MainForegroundEntryRoutingPolicy.resolve(
                    foregroundEntryId = 9L,
                    evaluatedForegroundEntryId = 8L,
                    currentMainForegroundEntryId = 0L,
                    currentRoute = MAIN_ROUTE,
                    mainRouteName = MAIN_ROUTE,
                    suppressForegroundAutoInput = true,
                )

            state shouldBe
                MainForegroundEntryRoutingPolicy.State(
                    evaluatedForegroundEntryId = 9L,
                    mainForegroundEntryId = 0L,
                )
        }
    }

    private companion object {
        const val MAIN_ROUTE = "com.lomo.app.navigation.NavRoute.Main"
        const val SETTINGS_ROUTE = "com.lomo.app.navigation.NavRoute.Settings"
    }
}
