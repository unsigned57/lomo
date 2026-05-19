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


import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: ResolveMainMemoQueryUseCase
 * - Behavior focus: main-memo query resolution between search text and all-memos fallback.
 * - Observable outcomes: ResolvedQuery variant returned for blank vs non-blank input.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: UI search interactions and repository querying behavior.
 */
class ResolveMainMemoQueryUseCaseTest : DomainFunSpec() {
    private val useCase = ResolveMainMemoQueryUseCase()
    init {
        test("search text is used when query is not blank") {
            val resolved = useCase(query = "memo")

            resolved shouldBe ResolveMainMemoQueryUseCase.ResolvedQuery.BySearchText("memo")
        }

        test("all memos is selected when query is blank") {
            val resolved = useCase(query = " ")

            resolved shouldBe ResolveMainMemoQueryUseCase.ResolvedQuery.AllMemos
        }
    }
}
