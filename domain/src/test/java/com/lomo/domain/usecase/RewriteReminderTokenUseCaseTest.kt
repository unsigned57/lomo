/*
 * Behavior Contract:
 * - Unit under test: RewriteReminderTokenUseCase
 * - Behavior focus: replace one marker token in content with a new canonical token, preserving text around it; advance fired count; mark done.
 * - Observable outcomes: returned string with the targeted marker substituted exactly once.
 * - TDD proof: fails because RewriteReminderTokenUseCase does not yet exist.
 * - Excludes: persistence, scheduling.
 */
package com.lomo.domain.usecase

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


import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RewriteReminderTokenUseCaseTest : FunSpec({
    val parse = ParseRemindersUseCase()
    val rewrite = RewriteReminderTokenUseCase()

    test("replaces token at the start of content") {
        val content = "@2026-05-20-09:00 buy milk"
        val marker = parse(content).single()

        val updated = rewrite(content = content, marker = marker, newToken = "@2026-05-20-09:00.done")

        updated shouldBe "@2026-05-20-09:00.done buy milk"
    }

    test("replaces token in the middle of content") {
        val content = "before @2026-05-20-09:00x3 after"
        val marker = parse(content).single()

        val updated = rewrite(content, marker, "@2026-05-20-09:00x3.1")

        updated shouldBe "before @2026-05-20-09:00x3.1 after"
    }

    test("replaces only the targeted marker when multiple exist") {
        val content = "first @2026-05-20-09:00 then @2026-06-01-10:30x2"
        val second = parse(content)[1]

        val updated = rewrite(content, second, "@2026-06-01-10:30x2.1")

        updated shouldBe "first @2026-05-20-09:00 then @2026-06-01-10:30x2.1"
    }

    test("rewriting is idempotent when newToken equals raw") {
        val content = "@2026-05-20-09:00 ping"
        val marker = parse(content).single()

        rewrite(content, marker, marker.raw) shouldBe content
    }
})
