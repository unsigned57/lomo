package com.lomo.app.feature.main

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SidebarViewModel
 * - Behavior focus: sidebar aggregate projection and reachable search-filter delegation.
 * - Observable outcomes: stats counts, parsed date map, sorted tags, and propagated search filter state.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Test Change Justification: reason category = product contract changed; removed pinned-tag assertions and toggle coverage because tag pinning is being rolled back before ship, while the original aggregation, ordering, and filter-delegation risks remain covered by the retained assertions below.
 * - Excludes: Compose sidebar rendering, calendar drawing behavior, and repository implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SidebarViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }
    private lateinit var memoRepository: MemoRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var stateHolder: MainSidebarStateHolder
    private lateinit var appConfigCoordinator: AppConfigUiCoordinator

    init {
        beforeTest {
memoRepository = mockk(relaxed = true)
            appConfigRepository = mockk(relaxed = true)
            every { appConfigRepository.getSidebarTagOrder() } returns flowOf(emptyList())
            stateHolder = MainSidebarStateHolder()
            appConfigCoordinator = AppConfigUiCoordinator(appConfigRepository)
        }
    }

    init {
        test("sidebarUiState aggregates stats filters invalid dates and sorts tags") {
            runTest {
                every { memoRepository.getMemoCountFlow() } returns flowOf(7)
                every { memoRepository.getMemoCountByDateFlow() } returns
                    flowOf(
                        mapOf(
                            "2026_03_24" to 3,
                            "2026-03-23" to 2,
                            "not-a-date" to 99,
                        ),
                    )
                every { memoRepository.getTagCountsFlow() } returns
                    flowOf(
                        listOf(
                            MemoTagCount(name = "zeta", count = 4),
                            MemoTagCount(name = "alpha", count = 4),
                            MemoTagCount(name = "beta", count = 10),
                        ),
                    )

                val viewModel =
                    SidebarViewModel(
                        memoUiCoordinator = MemoUiCoordinator(memoRepository),
                        stateHolder = stateHolder,
                        appConfigCoordinator = appConfigCoordinator,
                    )

                val state = viewModel.sidebarUiState.first { it.stats.memoCount == 7 && it.tags.isNotEmpty() }

                (state.stats.memoCount) shouldBe (7)
                (state.stats.tagCount) shouldBe (3)
                (state.stats.dayCount) shouldBe (2)
                (state.memoCountByDate) shouldBe (mapOf(
                        LocalDate.of(2026, 3, 24) to 3,
                        LocalDate.of(2026, 3, 23) to 2,
                    ))
                (state.tags) shouldBe (listOf(
                        com.lomo.ui.component.navigation.SidebarTag(name = "beta", count = 10),
                        com.lomo.ui.component.navigation.SidebarTag(name = "alpha", count = 4),
                        com.lomo.ui.component.navigation.SidebarTag(name = "zeta", count = 4),
                    ))
            }
        }
    }

    init {
        test("onSearch delegates query update to state holder") {
            runTest {
                val viewModel =
                    SidebarViewModel(
                        memoUiCoordinator = MemoUiCoordinator(memoRepository),
                        stateHolder = stateHolder,
                        appConfigCoordinator = appConfigCoordinator,
                    )

                viewModel.onSearch("meeting")

                (viewModel.searchQuery.value) shouldBe ("meeting")
            }
        }
    }

    init {
        test("clearFilters resets query") {
            runTest {
                val viewModel =
                    SidebarViewModel(
                        memoUiCoordinator = MemoUiCoordinator(memoRepository),
                        stateHolder = stateHolder,
                        appConfigCoordinator = appConfigCoordinator,
                    )

                viewModel.onSearch("meeting")

                viewModel.clearFilters()

                (viewModel.searchQuery.value) shouldBe ("")
            }
        }
    }

}
