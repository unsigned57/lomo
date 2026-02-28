package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.WorkspaceConfigSource
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK(relaxed = true)
    private lateinit var dataSource: WorkspaceConfigSource

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository = SettingsRepositoryImpl(dao, dataSource, dataStore)
    }

    @Test
    fun `setRootDirectory updates root then clears caches`() =
        runTest {
            stubMemoCacheClears()
            coEvery { dataSource.setRoot("/tmp/lomo") } just runs

            repository.setRootDirectory("/tmp/lomo")

            coVerifyOrder {
                dataSource.setRoot("/tmp/lomo")
                dao.clearMemoFileOutbox()
                dao.clearLocalFileState()
                dao.clearTagRefs()
                dao.clearAll()
                dao.clearTrash()
                dao.clearFts()
            }
            coVerify(exactly = 1) { dataSource.setRoot("/tmp/lomo") }
            verifyMemoCachesClearedExactlyOnce()
        }

    @Test
    fun `setRootDirectory does not clear caches when root update fails`() =
        runTest {
            coEvery { dataSource.setRoot("/tmp/lomo") } throws IllegalStateException("setRoot failed")

            val exception = runCatching { repository.setRootDirectory("/tmp/lomo") }.exceptionOrNull()

            assertTrue(exception is IllegalStateException)
            coVerify(exactly = 1) { dataSource.setRoot("/tmp/lomo") }
            verifyMemoCachesNotCleared()
        }

    @Test
    fun `updateRootUri updates root uri then clears caches`() =
        runTest {
            stubMemoCacheClears()
            coEvery { dataStore.updateRootUri("content://lomo/root") } just runs

            repository.updateRootUri("content://lomo/root")

            coVerifyOrder {
                dataStore.updateRootUri("content://lomo/root")
                dao.clearMemoFileOutbox()
                dao.clearLocalFileState()
                dao.clearTagRefs()
                dao.clearAll()
                dao.clearTrash()
                dao.clearFts()
            }
            coVerify(exactly = 1) { dataStore.updateRootUri("content://lomo/root") }
            verifyMemoCachesClearedExactlyOnce()
        }

    @Test
    fun `updateRootUri does not clear caches when root uri update fails`() =
        runTest {
            coEvery { dataStore.updateRootUri("content://lomo/root") } throws IllegalStateException("updateRootUri failed")

            val exception = runCatching { repository.updateRootUri("content://lomo/root") }.exceptionOrNull()

            assertTrue(exception is IllegalStateException)
            coVerify(exactly = 1) { dataStore.updateRootUri("content://lomo/root") }
            verifyMemoCachesNotCleared()
        }

    private fun stubMemoCacheClears() {
        coEvery { dao.clearMemoFileOutbox() } just runs
        coEvery { dao.clearLocalFileState() } just runs
        coEvery { dao.clearAll() } just runs
        coEvery { dao.clearTagRefs() } just runs
        coEvery { dao.clearTrash() } just runs
        coEvery { dao.clearFts() } just runs
    }

    private fun verifyMemoCachesClearedExactlyOnce() {
        coVerify(exactly = 1) { dao.clearMemoFileOutbox() }
        coVerify(exactly = 1) { dao.clearLocalFileState() }
        coVerify(exactly = 1) { dao.clearAll() }
        coVerify(exactly = 1) { dao.clearTagRefs() }
        coVerify(exactly = 1) { dao.clearTrash() }
        coVerify(exactly = 1) { dao.clearFts() }
    }

    private fun verifyMemoCachesNotCleared() {
        coVerify(exactly = 0) { dao.clearMemoFileOutbox() }
        coVerify(exactly = 0) { dao.clearLocalFileState() }
        coVerify(exactly = 0) { dao.clearAll() }
        coVerify(exactly = 0) { dao.clearTagRefs() }
        coVerify(exactly = 0) { dao.clearTrash() }
        coVerify(exactly = 0) { dao.clearFts() }
    }
}
