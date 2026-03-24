package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.repository.GitSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LoadMemoVersionHistoryUseCase
 * - Behavior focus: version-history lookups delegate using the memo's day bucket and timestamp.
 * - Observable outcomes: observed git-enabled state and returned version list for the requested memo.
 * - Excludes: git repository history traversal and markdown parsing internals.
 */
class LoadMemoVersionHistoryUseCaseTest {
    private val gitSyncRepository: GitSyncRepository = mockk()
    private val useCase = LoadMemoVersionHistoryUseCase(gitSyncRepository)

    @Test
    fun `observeGitSyncEnabled exposes repository flow`() =
        runTest {
            every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(true)

            assertEquals(true, useCase.observeGitSyncEnabled().first())
        }

    @Test
    fun `invoke loads version history for memo date key and timestamp`() =
        runTest {
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 123L,
                    content = "current",
                    rawContent = "- 10:00 current",
                    dateKey = "2026_03_24",
                )
            val versions =
                listOf(
                    MemoVersion(
                        commitHash = "abc",
                        commitTime = 456L,
                        commitMessage = "update",
                        memoContent = "previous",
                    ),
                )
            coEvery {
                gitSyncRepository.getMemoVersionHistory("2026_03_24", 123L)
            } returns versions

            val result = useCase(memo)

            assertEquals(versions, result)
            coVerify(exactly = 1) {
                gitSyncRepository.getMemoVersionHistory("2026_03_24", 123L)
            }
        }
}
