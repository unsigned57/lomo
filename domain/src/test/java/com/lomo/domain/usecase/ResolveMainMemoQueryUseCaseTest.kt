package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveMainMemoQueryUseCaseTest {
    private val useCase = ResolveMainMemoQueryUseCase()

    @Test
    fun `tag query has higher priority than search text`() {
        val resolved = useCase(query = "memo", selectedTag = "work")

        assertEquals(
            ResolveMainMemoQueryUseCase.ResolvedQuery.ByTag("work"),
            resolved,
        )
    }

    @Test
    fun `search text is used when tag is blank`() {
        val resolved = useCase(query = "memo", selectedTag = " ")

        assertEquals(
            ResolveMainMemoQueryUseCase.ResolvedQuery.BySearchText("memo"),
            resolved,
        )
    }

    @Test
    fun `all memos is selected when both tag and query are blank`() {
        val resolved = useCase(query = "", selectedTag = null)

        assertEquals(
            ResolveMainMemoQueryUseCase.ResolvedQuery.AllMemos,
            resolved,
        )
    }
}
