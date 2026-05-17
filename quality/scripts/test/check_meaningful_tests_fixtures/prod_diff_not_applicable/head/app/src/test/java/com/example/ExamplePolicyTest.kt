package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: ExamplePolicy
 * - Owning layer: app
 * - Priority tier: P1
 *
 * Scenario matrix:
 * - Happy: token returns the refreshed value.
 * - Boundary: token is stable across repeated calls.
 * - Failure: no failure path is expected for the trivial policy.
 * - Must-not-happen: the old token must not survive the change.
 *
 * Observable outcomes:
 * - returned token string.
 *
 * Red phase:
 * - Not applicable - test-only coverage addition; no production change.
 *
 * Excludes:
 * - repository wiring and UI rendering.
 */
class ExamplePolicyTest : FunSpec({
    test("token returns updated value") {
        ExamplePolicy().token() shouldBe "new"
    }
})
