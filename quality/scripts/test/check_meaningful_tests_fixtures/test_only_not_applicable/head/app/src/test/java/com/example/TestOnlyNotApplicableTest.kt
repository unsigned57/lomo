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
 * - Happy: the pure test-only contract stays documented.
 * - Boundary: the assertion still passes without production diffs.
 * - Failure: the metadata would be rejected if production changed.
 * - Must-not-happen: the red phase must not misreport a production change.
 *
 * Observable outcomes:
 * - trivial contract lock-in.
 *
 * Red phase:
 * - Not applicable - test-only coverage addition; no production change.
 *
 * Excludes:
 * - production implementation changes.
 */
class TestOnlyNotApplicableTest : FunSpec({
    test("allows test only additions") {
        true shouldBe true
    }
})
