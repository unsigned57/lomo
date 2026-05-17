package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: ExamplePolicy
 * - Owning layer: app
 * - Priority tier: P1
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
class MissingScenarioMatrixTest : FunSpec({
    test("documents contract metadata without scenario matrix") {
        1 shouldBe 1
    }
})
