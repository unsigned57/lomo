package com.lomo.app

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SecuritySessionState
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: splash admission uses security readiness; owning layer: app; priority: P1.
 * Scenarios:
 * - Given unknown security, when evaluated, then keep the splash until the privacy decision.
 * - Given known security and loading workspace, when evaluated, then show the app loading surface.
 * Observable outcomes: splash admission for every security state independent of memo IO.
 * TDD proof: RED/GREEN ./kotlin test --include-module=app
 * --include-classes=com.lomo.app.StartupSplashReadinessTest (ui-bugs/app-red.log and app-green.log).
 * Excludes: device startup duration and launcher rendering.
 */
class StartupSplashReadinessTest : AppFunSpec() {
    init {
        test("given known security when workspace is still loading then draw the app") {
            listOf(
                SecuritySessionState.LockOff,
                SecuritySessionState.Unlocked,
                SecuritySessionState.Locked,
                SecuritySessionState.StorageFailure,
            ).forEach { session ->
                shouldKeepMainSplashVisible(session) shouldBe false
            }
        }
        test("given unknown security when the list is ready then wait for the privacy decision") {
            shouldKeepMainSplashVisible(SecuritySessionState.Unknown) shouldBe true
        }
    }
}
