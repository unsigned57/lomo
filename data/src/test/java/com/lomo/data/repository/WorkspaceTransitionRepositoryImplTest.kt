package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class WorkspaceTransitionRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var memoDao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    private lateinit var repository: WorkspaceTransitionRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            WorkspaceTransitionRepositoryImpl(
                memoWriteDao = memoDao,
                memoOutboxDao = memoDao,
                memoTagDao = memoDao,
                memoFtsDao = memoDao,
                memoTrashDao = memoDao,
                localFileStateDao = localFileStateDao,
            )
    }

    @Test
    fun `clearMemoStateAfterWorkspaceTransition clears memo dependent tables in expected order`() =
        runTest {
            coEvery { memoDao.clearMemoFileOutbox() } just runs
            coEvery { localFileStateDao.clearAll() } just runs
            coEvery { memoDao.clearTagRefs() } just runs
            coEvery { memoDao.clearAll() } just runs
            coEvery { memoDao.clearTrash() } just runs
            coEvery { memoDao.clearFts() } just runs

            repository.clearMemoStateAfterWorkspaceTransition()

            coVerifyOrder {
                memoDao.clearMemoFileOutbox()
                localFileStateDao.clearAll()
                memoDao.clearTagRefs()
                memoDao.clearAll()
                memoDao.clearTrash()
                memoDao.clearFts()
            }
        }
}
