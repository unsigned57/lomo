package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: ExamplePolicy
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: prove scenario-shape enforcement rejects metadata-only contracts.
 *
 * Scenarios:
 * - This intentionally omits Given/When/Then wording so the smoke test proves scenario-shape enforcement.
 *
 * Observable outcomes:
 * - returned token string.
 *
 * TDD proof:
 * - Not applicable - test-only migration; no production change.
 *
 * Excludes:
 * - repository wiring and UI rendering.
 */
class MissingScenarioMatrixTest : FunSpec({
    test("documents contract metadata without scenario matrix") {
        1 shouldBe 1
    }
})
