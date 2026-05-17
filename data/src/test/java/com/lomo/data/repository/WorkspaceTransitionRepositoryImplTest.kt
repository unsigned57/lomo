package com.lomo.data.repository


import com.lomo.data.local.dao.LocalFileStateDao
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: WorkspaceTransitionRepositoryImpl
 * - Behavior focus: Handle transition logic between workspaces.
 * - Observable outcomes: State transitions and callbacks.
 * - Red phase: Verified by stripping transition safeguards.
 * - Excludes: IO level exceptions.
 */
class WorkspaceTransitionRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("clearMemoStateAfterWorkspaceTransition clears memo dependent tables in expected order") { `clearMemoStateAfterWorkspaceTransition clears memo dependent tables in expected order`() }

        test("clearMemoStateAfterWorkspaceTransition wraps cleanup in provided transaction runner") { `clearMemoStateAfterWorkspaceTransition wraps cleanup in provided transaction runner`() }
    }


    @MockK(relaxed = true)
    private lateinit var memoDao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    private lateinit var repository: WorkspaceTransitionRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        repository =
            WorkspaceTransitionRepositoryImpl(
                memoWriteDao = memoDao,
                memoOutboxDao = memoDao,
                memoTagDao = memoDao,
                memoImageDao = memoDao,
                memoTrashDao = memoDao,
                localFileStateDao = localFileStateDao,
                runInTransaction = { block -> block() },
            )
    }

    private fun `clearMemoStateAfterWorkspaceTransition clears memo dependent tables in expected order`() =
        runTest {
            coEvery { memoDao.clearMemoFileOutbox() } just runs
            coEvery { localFileStateDao.clearAll() } just runs
            coEvery { memoDao.clearTagRefs() } just runs
            coEvery { memoDao.clearImageRefs() } just runs
            coEvery { memoDao.clearAll() } just runs
            coEvery { memoDao.clearTrash() } just runs

            repository.clearMemoStateAfterWorkspaceTransition()

            coVerifyOrder {
                memoDao.clearMemoFileOutbox()
                localFileStateDao.clearAll()
                memoDao.clearTagRefs()
                memoDao.clearImageRefs()
                memoDao.clearAll()
                memoDao.clearTrash()
            }
        }

    private fun `clearMemoStateAfterWorkspaceTransition wraps cleanup in provided transaction runner`() =
        runTest {
            val callTrace = mutableListOf<String>()
            val transactionalRepository =
                WorkspaceTransitionRepositoryImpl(
                    memoWriteDao = memoDao,
                    memoOutboxDao = memoDao,
                    memoTagDao = memoDao,
                    memoImageDao = memoDao,
                    memoTrashDao = memoDao,
                    localFileStateDao = localFileStateDao,
                    runInTransaction = { block ->
                        callTrace += "tx-start"
                        block()
                        callTrace += "tx-end"
                    },
                )
            coEvery { memoDao.clearMemoFileOutbox() } coAnswers { callTrace += "outbox" }
            coEvery { localFileStateDao.clearAll() } coAnswers { callTrace += "local-state" }
            coEvery { memoDao.clearTagRefs() } coAnswers { callTrace += "tags" }
            coEvery { memoDao.clearImageRefs() } coAnswers { callTrace += "images" }
            coEvery { memoDao.clearAll() } coAnswers { callTrace += "memos" }
            coEvery { memoDao.clearTrash() } coAnswers { callTrace += "trash" }

            transactionalRepository.clearMemoStateAfterWorkspaceTransition()

            callTrace shouldBe listOf(
                    "tx-start",
                    "outbox",
                    "local-state",
                    "tags",
                    "images",
                    "memos",
                    "trash",
                    "tx-end",
                )
        }
}
