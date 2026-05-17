package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertEquals

/*
 * Test Contract:
 * - Unit under test: ExamplePolicy
 * - Owning layer: app
 * - Priority tier: P1
 *
 * Scenario matrix:
 * - Happy: migrated assertions still verify the token value.
 * - Boundary: the file should not mix assertion stacks during the transition.
 * - Failure: validation fails when both JUnit and Kotest assertions are imported.
 * - Must-not-happen: mixed assertion styles must not land in one file.
 *
 * Observable outcomes:
 * - mixed assertion imports detected by the script.
 *
 * Red phase:
 * - Not applicable - test-only coverage addition; no production change.
 *
 * Excludes:
 * - production behavior and runtime wiring.
 */
class HalfMigratedTest : FunSpec({
    test("shows the half migrated state") {
        ExamplePolicy().token() shouldBe "old"
        assertEquals("old", ExamplePolicy().token())
    }
})
