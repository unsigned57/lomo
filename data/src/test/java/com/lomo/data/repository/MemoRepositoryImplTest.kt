package com.lomo.data.repository

import com.lomo.data.local.dao.ImageCacheDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoTokenDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileDataSource
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK(relaxed = true)
    private lateinit var imageCacheDao: ImageCacheDao

    @MockK(relaxed = true)
    private lateinit var tokenDao: MemoTokenDao

    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var synchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var parser: MarkdownParser

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var repository: MemoRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            MemoRepositoryImpl(
                dao = dao,
                imageCacheDao = imageCacheDao,
                tokenDao = tokenDao,
                dataSource = dataSource,
                synchronizer = synchronizer,
                parser = parser,
                dataStore = dataStore,
            )
    }

    @Test
    fun `saveMemo delegates to synchronizer`() =
        runTest {
            coEvery { synchronizer.saveMemo(any(), any()) } just runs
            repository.saveMemo("content", timestamp = 123L)
            coVerify(exactly = 1) { synchronizer.saveMemo("content", 123L) }
        }

    @Test
    fun `saveMemo propagates synchronizer exception`() =
        runTest {
            coEvery {
                synchronizer.saveMemo(any(), any())
            } throws IllegalStateException("sync failed")

            val thrown =
                runCatching {
                    repository.saveMemo("content", timestamp = 456L)
                }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
        }
}
