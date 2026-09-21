package com.lomo.app

/*
 * Behavior Contract:
 * - Unit under test: app-lock gate visibility and auto-unlock derivations from SecuritySessionState.
 * - Capability: derive the lock-gate visibility and auto-unlock prompt policy purely from the
 *   shared SecuritySessionState.
 * - Behavior focus:
 *   1. The lock gate covers Unknown, Locked, and StorageFailure so content is not shown without
 *      an authorizing session.
 *   2. Auto-unlock fires only for Locked, once, while no prompt is in flight.
 *   3. Enabling lock while already LockOff/Unlocked is a session-machine concern (in-session
 *      enable stays Unlocked); the gate simply hides for those states.
 * Scenarios:
 * - Given Unknown, Locked, or StorageFailure, when the gate resolves, then it is visible.
 * - Given LockOff or Unlocked, when the gate resolves, then it is hidden.
 * - Given Locked and no prompt in flight, when auto-unlock is queried, then it fires once.
 * Observable outcomes: booleans from resolveAppLockGateVisible and shouldAutoRequestAppLockUnlock.
 * TDD proof: AppLockPolicyTest; session machine is locked in domain SecuritySessionTest.
 * Excludes: BiometricPrompt wiring, DataStore persistence, Compose tree topology, foreground timer.
 *
 * Test Change Justification:
 * - Reason category: security session contract replacement.
 * - Old behavior/assertion being replaced: Compose-owned hasUnlockedThisLaunch plus nullable
 *   appLockEnabled booleans, including "gate hidden while preference is still resolving".
 * - Why old assertion is no longer correct: unresolved preference is Unknown and must not show
 *   unlocked content; UI, credential reads, and workers share one SecuritySessionState.
 * - Coverage preserved by: gate visible for Unknown/Locked/StorageFailure; auto-unlock only for
 *   Locked; LockOff/Unlocked hide the gate.
 * - Why this is not fitting the test to the implementation: assertions still check the user-visible
 *   gate and auto-prompt policy, not private Compose remember flags.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SecuritySessionState
import io.kotest.matchers.shouldBe

class AppLockPolicyTest : AppFunSpec() {
    init {
        test("gate is visible while the session is still unknown") {
            resolveAppLockGateVisible(SecuritySessionState.Unknown) shouldBe true
            resolveAppLockConfigLoading(SecuritySessionState.Unknown) shouldBe true
        }

        test("gate stays hidden when lock is off") {
            resolveAppLockGateVisible(SecuritySessionState.LockOff) shouldBe false
        }

        test("gate is visible when the session is locked") {
            resolveAppLockGateVisible(SecuritySessionState.Locked) shouldBe true
        }

        test("gate stays hidden after authentication") {
            resolveAppLockGateVisible(SecuritySessionState.Unlocked) shouldBe false
        }

        test("gate is visible when preference storage failed") {
            resolveAppLockGateVisible(SecuritySessionState.StorageFailure) shouldBe true
        }

        test("auto-unlock fires once when the session is locked") {
            shouldAutoRequestAppLockUnlock(
                session = SecuritySessionState.Locked,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe true
        }

        test("auto-unlock does not refire once a prompt has already been scheduled") {
            shouldAutoRequestAppLockUnlock(
                session = SecuritySessionState.Locked,
                hasRequestedAutoUnlock = true,
                unlockPromptInProgress = false,
            ) shouldBe false
        }

        test("auto-unlock does not refire while a prompt is still in flight") {
            shouldAutoRequestAppLockUnlock(
                session = SecuritySessionState.Locked,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = true,
            ) shouldBe false
        }

        test("auto-unlock never fires when the session is unlocked") {
            shouldAutoRequestAppLockUnlock(
                session = SecuritySessionState.Unlocked,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe false
        }

        test("auto-unlock never fires for storage failure") {
            shouldAutoRequestAppLockUnlock(
                session = SecuritySessionState.StorageFailure,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe false
        }

        test("auto-unlock never fires while the session is unknown") {
            shouldAutoRequestAppLockUnlock(
                session = SecuritySessionState.Unknown,
                hasRequestedAutoUnlock = false,
                unlockPromptInProgress = false,
            ) shouldBe false
        }
    }
}
