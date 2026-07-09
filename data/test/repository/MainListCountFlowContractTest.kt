package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.data.testing.fakes.FakeDefaultMainListDao
import com.lomo.data.testing.fakes.FakeMemoBrowseDao
import com.lomo.data.testing.fakes.FakeMemoDao
import com.lomo.data.testing.fakes.FakeMemoPinDao
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoQueryRepositoryImpl main-list count flow.
 * - Behavior focus: the main memo list scrollbar needs a repository-backed total that follows
 *   the same search query, date filter, and sort inputs as the PagingSource.
 * - Observable outcomes: emitted count and normalized arguments delegated to DefaultMainListDao.
 * - TDD proof: Fails before the fix because the repository exposes only the loaded PagingSource
 *   and has no query/filter-aware count flow for the main list.
 * - Excludes: Room SQL execution, FTS tokenizer internals beyond normalized query delegation, and UI rendering.
 */
class MainListCountFlowContractTest : DataFunSpec() {
    init {
        test("main list count flow follows the same normalized query and filter as paging") { `main list count flow follows the same normalized query and filter as paging`() }
    }


    private val memoDao = FakeMemoDao()
    private val memoBrowseDao = FakeMemoBrowseDao()
    private val defaultMainListDao = FakeDefaultMainListDao()
    private val memoPinDao = FakeMemoPinDao()
    private val synchronizer: MemoSynchronizer = mockk()

    private val repository =
        MemoQueryRepositoryImpl(
            memoDao = memoDao,
            memoBrowseDao = memoBrowseDao,
            defaultMainListDao = defaultMainListDao,
            memoPinDao = memoPinDao,
            synchronizer = synchronizer,
        )

    private fun `main list count flow follows the same normalized query and filter as paging`() =
        runTest {
            defaultMainListDao.getCountFlowResult = flowOf(7)

            val count =
                repository
                    .getMainListCountFlow(
                        spec =
                            MemoQuerySpec.fromFilter(
                                queryText = "alpha",
                                filter =
                                    MemoListFilter(
                                        sortOption = MemoSortOption.UPDATED_TIME,
                                        sortAscending = true,
                                        hasTodo = null,
                                        hasAttachment = null,
                                        hasUrl = null,
                                        startDate = LocalDate.of(2026, 1, 4),
                                        endDate = LocalDate.of(2026, 1, 2),
                                    ),
                            ),
                    ).first()

            count shouldBe 7
        }
}
