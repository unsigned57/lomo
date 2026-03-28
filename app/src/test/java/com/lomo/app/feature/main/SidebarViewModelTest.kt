package com.lomo.app.feature.main

import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/*
 * Test Contract:
 * - Unit under test: SidebarViewModel
 * - Behavior focus: sidebar aggregate projection and reachable search-filter delegation.
 * - Observable outcomes: stats counts, parsed date map, sorted tags, and propagated search filter state.
 * - Red phase: Not applicable - unreachable selectedTag refactor; production change removes dead API without altering reachable behavior.
 * - Excludes: Compose sidebar rendering, calendar drawing behavior, and repository implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SidebarViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: MemoRepository
    private lateinit var stateHolder: MainSidebarStateHolder

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        stateHolder = MainSidebarStateHolder()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sidebarUiState aggregates stats filters invalid dates and sorts tags`() =
        runTest {
            every { repository.getMemoCountFlow() } returns flowOf(7)
            every { repository.getMemoCountByDateFlow() } returns
                flowOf(
                    mapOf(
                        "2026_03_24" to 3,
                        "2026-03-23" to 2,
                        "not-a-date" to 99,
                    ),
                )
            every { repository.getTagCountsFlow() } returns
                flowOf(
                    listOf(
                        MemoTagCount(name = "zeta", count = 4),
                        MemoTagCount(name = "alpha", count = 4),
                        MemoTagCount(name = "beta", count = 10),
                    ),
                )

            val viewModel =
                SidebarViewModel(
                    memoUiCoordinator = MemoUiCoordinator(repository),
                    stateHolder = stateHolder,
                )

            val state = viewModel.sidebarUiState.first { it.stats.memoCount == 7 && it.tags.isNotEmpty() }

            assertEquals(7, state.stats.memoCount)
            assertEquals(3, state.stats.tagCount)
            assertEquals(2, state.stats.dayCount)
            assertEquals(
                mapOf(
                    LocalDate.of(2026, 3, 24) to 3,
                    LocalDate.of(2026, 3, 23) to 2,
                ),
                state.memoCountByDate,
            )
            assertEquals(
                listOf(
                    com.lomo.ui.component.navigation.SidebarTag(name = "beta", count = 10),
                    com.lomo.ui.component.navigation.SidebarTag(name = "alpha", count = 4),
                    com.lomo.ui.component.navigation.SidebarTag(name = "zeta", count = 4),
                ),
                state.tags,
            )
        }

    @Test
    fun `onSearch delegates query update to state holder`() =
        runTest {
            val viewModel =
                SidebarViewModel(
                    memoUiCoordinator = MemoUiCoordinator(repository),
                    stateHolder = stateHolder,
                )

            viewModel.onSearch("meeting")
            advanceUntilIdle()

            assertEquals("meeting", viewModel.searchQuery.value)
        }

    @Test
    fun `clearFilters resets query`() =
        runTest {
            val viewModel =
                SidebarViewModel(
                    memoUiCoordinator = MemoUiCoordinator(repository),
                    stateHolder = stateHolder,
                )

            viewModel.onSearch("meeting")
            advanceUntilIdle()

            viewModel.clearFilters()
            advanceUntilIdle()

            assertEquals("", viewModel.searchQuery.value)
        }
}
