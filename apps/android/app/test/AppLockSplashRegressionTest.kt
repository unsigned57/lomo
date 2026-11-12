package com.lomo.app

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SecuritySessionState
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: a locked launch displays the retryable gate; owning layer: app; priority: P1.
 * Scenarios:
 * - Given locked security and a loading memo list, when the splash is evaluated, then it exits.
 * - Given unlocked security and a loading memo list, when evaluated, then the app loading surface is visible.
 * Observable outcomes: splash visibility, independent of workspace readiness.
 * TDD proof: RED ./kotlin test --include-module=app --include-classes='com.lomo.app.AppLockSplashRegressionTest';
 * locked startup returned true. GREEN: same command after the gate-aware policy.
 * Test Change Justification:
 * Reason category: requested startup behavior correction.
 * Old behavior/assertion being replaced: unlocked launches retain splash while memos load.
 * Why old assertion is no longer correct: storage IO must not block first draw or deferred work.
 * Coverage preserved by: locked gate visibility plus all security states in StartupSplashReadinessTest.
 * Why this is not fitting the test to the implementation: the admission follows the privacy decision.
 * Excludes: OS biometric hardware and visual layout.
 */
class AppLockSplashRegressionTest : AppFunSpec() {
    init {
        test("given locked startup when memos are loading then the gate is visible behind the prompt") {
            shouldKeepMainSplashVisible(SecuritySessionState.Locked) shouldBe false
        }
        test("given unlocked startup when memos are loading then the app can draw") {
            shouldKeepMainSplashVisible(SecuritySessionState.LockOff) shouldBe false
            shouldKeepMainSplashVisible(SecuritySessionState.Unlocked) shouldBe false
        }
    }
}
