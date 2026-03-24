package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: RestoreMemoVersionUseCase
 * - Behavior focus: selected historical content is restored through the update-content path.
 * - Observable outcomes: delegated update request with the chosen memo version content.
 * - Excludes: content validation, delete-vs-update branching, and repository persistence details.
 */
class RestoreMemoVersionUseCaseTest {
    private val updateMemoContentUseCase: UpdateMemoContentUseCase = mockk(relaxed = true)
    private val useCase = RestoreMemoVersionUseCase(updateMemoContentUseCase)

    @Test
    fun `invoke restores selected version content through update use case`() =
        runTest {
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 123L,
                    content = "current",
                    rawContent = "- 10:00 current",
                    dateKey = "2026_03_24",
                )
            val version =
                MemoVersion(
                    commitHash = "abc",
                    commitTime = 999L,
                    commitMessage = "restore target",
                    memoContent = "historical body",
                )
            coEvery { updateMemoContentUseCase(memo, "historical body") } returns Unit

            useCase(memo, version)

            coVerify(exactly = 1) { updateMemoContentUseCase(memo, "historical body") }
        }
}
