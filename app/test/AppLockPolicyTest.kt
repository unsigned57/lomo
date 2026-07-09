package com.lomo.app

/*
 * Behavior Contract:
 * - Unit under test: app-lock gate visibility and auto-unlock derivations.
 * - Behavior focus:
 *   1. The lock gate is only visible while the user has app lock enabled AND has not yet unlocked
 *      the current launch — toggling the setting mid-session must never cause the gate to appear,
 *      because that would tear down the active sub-screen tree (Settings sub-page, etc.).
 *   2. The launcher auto-fires the biometric prompt exactly once per launch and only when nobody
 *      has already unlocked or queued a prompt.
 * - Observable outcomes: booleans returned by resolveAppLockGateVisible and
 *   shouldAutoRequestAppLockUnlock.
 * - TDD proof: regresses to true if a future change re-introduces the structural if/else
 *   short-circuit in MainActivity by collapsing the Composable's `isGateVisible` derivation back
 *   to a single eager boolean expression and forgetting the "already unlocked" guard.
 * - Excludes: BiometricPrompt wiring, DataStore persistence, Compose tree topology.
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class AppLockPolicyTest : AppFunSpec() {
    init {
        test("gate stays hidden during the initial launch before the persisted lock flag has resolved") {
            resolveAppLockGateVisible(
                appLockEnabled = null,
                hasUnlockedThisLaunch = false,
            ) shouldBe false
        }

        test("gate stays hidden when lock is disabled at launch even before the unlock flag flips") {
            resolveAppLockGateVisible(
                appLockEnabled = false,
                hasUnlockedThisLaunch = false,
            ) shouldBe false
        }

        test("gate becomes visible on cold start with lock enabled and no prior unlock") {
            resolveAppLockGateVisible(
                appLockEnabled = true,
                hasUnlockedThisLaunch = false,
            ) shouldBe true
        }

        test("gate stays hidden after the user enables lock from within an already-unlocked session") {
            resolveAppLockGateVisible(
                appLockEnabled = true,
                hasUnlockedThisLaunch = true,
            ) shouldBe false
        }

        test("auto-unlock fires once when lock is on and the launch has not been unlocked yet") {
            shouldAutoRequestAppLockUnlock(
                appLockEnabled = true,
                hasUnlockedThisLaunch = false,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe true
        }

        test("auto-unlock does not refire once a prompt has already been scheduled") {
            shouldAutoRequestAppLockUnlock(
                appLockEnabled = true,
                hasUnlockedThisLaunch = false,
                hasRequestedAutoUnlock = true,
                unlockPromptInProgress = false,
            ) shouldBe false
        }

        test("auto-unlock does not refire while a prompt is still in flight") {
            shouldAutoRequestAppLockUnlock(
                appLockEnabled = true,
                hasUnlockedThisLaunch = false,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = true,
            ) shouldBe false
        }

        test("auto-unlock does not refire after the user has successfully unlocked the launch") {
            shouldAutoRequestAppLockUnlock(
                appLockEnabled = true,
                hasUnlockedThisLaunch = true,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe false
        }

        test("auto-unlock never fires when lock is freshly enabled mid-session") {
            shouldAutoRequestAppLockUnlock(
                appLockEnabled = true,
                hasUnlockedThisLaunch = true,
                hasRequestedAutoUnlock = true,
                unlockPromptInProgress = false,
            ) shouldBe false
        }

        test("auto-unlock never fires when the persisted lock flag is still resolving") {
            shouldAutoRequestAppLockUnlock(
                appLockEnabled = null,
                hasUnlockedThisLaunch = false,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe false
        }
    }
}
