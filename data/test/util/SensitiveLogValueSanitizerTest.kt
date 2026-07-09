package com.lomo.data.util

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: SensitiveLogValueSanitizer
 * - Behavior focus: Strip credentials from logs.
 * - Observable outcomes: Sanitized strings.
 * - TDD proof: Verified by checking for leaked passwords.
 * - Excludes: none.
 */
class SensitiveLogValueSanitizerTest : DataFunSpec() {
    init {
        test("sanitizePathForLog keeps only a short basename prefix plus hash") { `sanitizePathForLog keeps only a short basename prefix plus hash`() }

        test("sanitizePathForLog is deterministic for the same input") { `sanitizePathForLog is deterministic for the same input`() }
    }


    private fun `sanitizePathForLog keeps only a short basename prefix plus hash`() {
        val sanitized = sanitizePathForLog("/vault/private/2026_03_25-secret.md")

        (sanitized.startsWith("2026")).shouldBeTrue()
        (sanitized.contains('#')).shouldBeTrue()
        (sanitized.contains("secret")).shouldBeFalse()
        (sanitized.contains("/vault/private")).shouldBeFalse()
    }

    private fun `sanitizePathForLog is deterministic for the same input`() {
        val path = "/vault/private/2026_03_25-secret.md"

        sanitizePathForLog(path) shouldBe sanitizePathForLog(path)
    }
}
