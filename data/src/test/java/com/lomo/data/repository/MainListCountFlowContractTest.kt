package com.lomo.data.repository

import com.lomo.data.local.dao.DefaultMainListDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: MemoQueryRepositoryImpl main-list count flow.
 * - Behavior focus: the main memo list scrollbar needs a repository-backed total that follows
 *   the same search query, date filter, and sort inputs as the PagingSource.
 * - Observable outcomes: emitted count and normalized arguments delegated to DefaultMainListDao.
 * - Red phase: Fails before the fix because the repository exposes only the loaded PagingSource
 *   and has no query/filter-aware count flow for the main list.
 * - Excludes: Room SQL execution, FTS tokenizer internals beyond normalized query delegation, and UI rendering.
 */
class MainListCountFlowContractTest {
    private val memoDao: MemoDao = mockk()
    private val memoBrowseDao: MemoBrowseDao = mockk()
    private val defaultMainListDao: DefaultMainListDao = mockk()
    private val memoPinDao: MemoPinDao = mockk()
    private val synchronizer: MemoSynchronizer = mockk(relaxed = true)

    private val repository =
        MemoQueryRepositoryImpl(
            memoDao = memoDao,
            memoBrowseDao = memoBrowseDao,
            defaultMainListDao = defaultMainListDao,
            memoPinDao = memoPinDao,
            synchronizer = synchronizer,
        )

    @Test
    fun `main list count flow follows the same normalized query and filter as paging`() =
        runTest {
            every {
                defaultMainListDao.getCountFlow(
                    query = "\"alpha\"*",
                    startDate = "2026_01_02",
                    endDate = "2026_01_04",
                    sortOption = "UPDATED_TIME",
                    sortAscending = true,
                )
            } returns flowOf(7)

            val count =
                repository
                    .getMainListCountFlow(
                        query = "alpha",
                        filter =
                            MemoListFilter(
                                sortOption = MemoSortOption.UPDATED_TIME,
                                sortAscending = true,
                                startDate = LocalDate.of(2026, 1, 4),
                                endDate = LocalDate.of(2026, 1, 2),
                            ),
                    ).first()

            assertEquals(7, count)
        }
}
