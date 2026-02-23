package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.FileDataSource
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var dao: MemoDao

    @MockK(relaxed = true)
    private lateinit var dataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var synchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var repository: MemoRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            MemoRepositoryImpl(
                dao = dao,
                dataSource = dataSource,
                synchronizer = synchronizer,
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

    @Test
    fun `getAllTags flattens deduplicates and sorts tags from memo rows`() =
        runTest {
            every { dao.getAllTagsFlow() } returns
                flowOf(
                    listOf(
                        "alpha",
                        "beta",
                        "gamma",
                    ),
                )

            val tags = repository.getAllTags().first()

            assertEquals(listOf("alpha", "beta", "gamma"), tags)
        }

    @Test
    fun `getTagCounts counts each tag once per memo row`() =
        runTest {
            every { dao.getTagCountsFlow() } returns
                flowOf(
                    listOf(
                        com.lomo.data.local.dao
                            .TagCountRow("alpha", 2),
                        com.lomo.data.local.dao
                            .TagCountRow("beta", 2),
                        com.lomo.data.local.dao
                            .TagCountRow("gamma", 1),
                    ),
                )

            val counts =
                repository
                    .getTagCounts()
                    .first()
                    .associate { it.name to it.count }

            assertEquals(mapOf("alpha" to 2, "beta" to 2, "gamma" to 1), counts)
        }
}
