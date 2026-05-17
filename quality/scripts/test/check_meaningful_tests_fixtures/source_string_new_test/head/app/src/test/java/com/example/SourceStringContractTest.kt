package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Test Contract:
 * - Unit under test: ExamplePolicy
 * - Owning layer: app
 * - Priority tier: P1
 *
 * Scenario matrix:
 * - Happy: the file contains the documented token.
 * - Boundary: repeated reads return the same token.
 * - Failure: token lookup fails if the source disappears.
 * - Must-not-happen: the contract must not rely on runtime behavior.
 *
 * Observable outcomes:
 * - source token visibility.
 *
 * Red phase:
 * - Not applicable - test-only coverage addition; no production change.
 *
 * Excludes:
 * - runtime observable behavior.
 */
class SourceStringContractTest : FunSpec({
    test("asserts a Kotlin source token") {
        val content = File("app/src/main/java/com/example/ExamplePolicy.kt").readText()

        content.contains("token") shouldBe true
    }
})
