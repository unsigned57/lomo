package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ResolveMainMemoQueryUseCase
 * - Behavior focus: main-memo query resolution between search text and all-memos fallback.
 * - Observable outcomes: ResolvedQuery variant returned for blank vs non-blank input.
 * - Red phase: Not applicable - test-only coverage alignment for current production contract.
 * - Excludes: UI search interactions and repository querying behavior.
 */
class ResolveMainMemoQueryUseCaseTest {
    private val useCase = ResolveMainMemoQueryUseCase()

    @Test
    fun `search text is used when query is not blank`() {
        val resolved = useCase(query = "memo")

        assertEquals(
            ResolveMainMemoQueryUseCase.ResolvedQuery.BySearchText("memo"),
            resolved,
        )
    }

    @Test
    fun `all memos is selected when query is blank`() {
        val resolved = useCase(query = " ")

        assertEquals(
            ResolveMainMemoQueryUseCase.ResolvedQuery.AllMemos,
            resolved,
        )
    }
}
