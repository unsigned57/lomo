package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: ResolveMainMemoQueryUseCase
 * - Behavior focus: main-memo query resolution between search text and all-memos fallback.
 * - Observable outcomes: ResolvedQuery variant returned for blank vs non-blank input.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: UI search interactions and repository querying behavior.
 */
class ResolveMainMemoQueryUseCaseTest : DomainFunSpec() {
    private val useCase = ResolveMainMemoQueryUseCase()
    init {
        test("search text is used when query is not blank") {
            val resolved = useCase(query = "memo")

            resolved shouldBe ResolveMainMemoQueryUseCase.ResolvedQuery.BySearchText("memo")
        }
    }
    init {
        test("all memos is selected when query is blank") {
            val resolved = useCase(query = " ")

            resolved shouldBe ResolveMainMemoQueryUseCase.ResolvedQuery.AllMemos
        }
    }
}
