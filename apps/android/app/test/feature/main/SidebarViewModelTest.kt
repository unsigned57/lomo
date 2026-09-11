package com.lomo.app.feature.main

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


import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.ObserveSidebarStatisticsUseCase
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk

@OptIn(ExperimentalCoroutinesApi::class)
class SidebarViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val stateHolder = MainSidebarStateHolder()
    private val appConfigCoordinator = AppConfigUiCoordinator(appConfigRepository)


    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            memoRepository.setActiveMemos(emptyList())
            memoRepository.resetCallCounts()
        }

        test("sidebarUiState aggregates stats filters invalid dates and sorts tags") {
            runTest {
                val memos = (1..10).map { id ->
                    val dateKey = when (id) {
                        1, 2, 3 -> "2026_03_24"
                        4, 5 -> "2026-03-23"
                        else -> "not-a-date"
                    }
                    val tags = when (id) {
                        in 1..4 -> listOf("beta", "zeta", "alpha")
                        else -> listOf("beta")
                    }
                    Memo(
                        id = id.toString(),
                        timestamp = id * 1000L,
                        content = "memo $id",
                        rawContent = "memo $id",
                        dateKey = dateKey,
                        localDate = when (id) {
                            1, 2, 3 -> LocalDate.of(2026, 3, 24)
                            4, 5 -> LocalDate.of(2026, 3, 23)
                            else -> null
                        },
                        tags = tags
                    )
                }
                memoRepository.setActiveMemos(memos)

                val viewModel = SidebarViewModel(
                    observeSidebarStatisticsUseCase = observeSidebarStatisticsUseCase(),
                    stateHolder = stateHolder,
                    appConfigCoordinator = appConfigCoordinator,
                )

                val state = viewModel.sidebarUiState.first { it.stats.memoCount == 10 && it.tags.isNotEmpty() }

                state.stats.memoCount shouldBe 10
                state.stats.tagCount shouldBe 3
                state.stats.dayCount shouldBe 2
                state.memoCountByDate shouldBe mapOf(
                    LocalDate.of(2026, 3, 24) to 3,
                    LocalDate.of(2026, 3, 23) to 2,
                )
                state.tags shouldBe listOf(
                    com.lomo.ui.component.navigation.SidebarTag(name = "beta", count = 10),
                    com.lomo.ui.component.navigation.SidebarTag(name = "alpha", count = 4),
                    com.lomo.ui.component.navigation.SidebarTag(name = "zeta", count = 4),
                )
            }
        }

        test("onSearch delegates query update to state holder") {
            runTest {
                val viewModel = SidebarViewModel(
                    observeSidebarStatisticsUseCase = observeSidebarStatisticsUseCase(),
                    stateHolder = stateHolder,
                    appConfigCoordinator = appConfigCoordinator,
                )

                viewModel.onSearch("meeting")

                viewModel.searchQuery.value shouldBe "meeting"
            }
        }

        test("clearFilters resets query") {
            runTest {
                val viewModel = SidebarViewModel(
                    observeSidebarStatisticsUseCase = observeSidebarStatisticsUseCase(),
                    stateHolder = stateHolder,
                    appConfigCoordinator = appConfigCoordinator,
                )

                viewModel.onSearch("meeting")

                viewModel.clearFilters()

                viewModel.searchQuery.value shouldBe ""
            }
        }
    }

    private fun observeSidebarStatisticsUseCase(): ObserveSidebarStatisticsUseCase =
        ObserveSidebarStatisticsUseCase(
            com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
        )
}
