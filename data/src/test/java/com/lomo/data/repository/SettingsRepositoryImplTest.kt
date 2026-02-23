package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.FileDataSource
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SettingsRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository = SettingsRepositoryImpl(dao, dataSource, dataStore)
    }

    @Test
    fun `setRootDirectory clears caches then updates root`() =
        runTest {
            coEvery { dao.clearAll() } just runs
            coEvery { dao.clearTagRefs() } just runs
            coEvery { dao.clearTrash() } just runs
            coEvery { dao.clearFts() } just runs
            coEvery { dataSource.setRoot("/tmp/lomo") } just runs

            repository.setRootDirectory("/tmp/lomo")

            coVerify(exactly = 1) { dao.clearAll() }
            coVerify(exactly = 1) { dao.clearTagRefs() }
            coVerify(exactly = 1) { dao.clearTrash() }
            coVerify(exactly = 1) { dao.clearFts() }
            coVerify(exactly = 1) { dataSource.setRoot("/tmp/lomo") }
        }
}
