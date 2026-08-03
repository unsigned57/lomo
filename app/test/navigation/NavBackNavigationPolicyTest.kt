/*
 * Behavior Contract:
 * - Unit under test: Nav back-navigation policy.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: leave child routes without adding a duplicate main destination.
 *
 * Scenarios:
 * - Given main is current, when the stack cannot pop, then navigation does not self-transition.
 * - Given a child is current, when the stack cannot pop, then navigation restores main.
 * - Given a route popped, when fallback is evaluated, then main is not added.
 *
 * Observable outcomes:
 * - The boolean command deciding whether the navigator adds the main destination.
 *
 * TDD proof:
 * - RED: the extracted policy did not exist before navigation back handling was made testable.
 *
 * Excludes:
 * - NavController implementation, Compose rendering and Android system-back dispatch.
 */
package com.lomo.app.navigation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NavBackNavigationPolicyTest : FunSpec({
    test("given the main route when back stack cannot pop then navigation does not self-transition") {
        shouldNavigateToMain(currentRoute = NavRouteSerialNames.MAIN, popBackStackSucceeded = false) shouldBe false
    }

    test("given a child route when back stack cannot pop then navigation restores main") {
        shouldNavigateToMain(currentRoute = "settings", popBackStackSucceeded = false) shouldBe true
    }

    test("given a route that popped successfully then navigation does not add main") {
        shouldNavigateToMain(currentRoute = "settings", popBackStackSucceeded = true) shouldBe false
    }
})
