/*
 * Behavior Contract:
 * - Unit under test: SearchViewModel filter wiring.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: re-run bounded page-backed search when filter controls change.
 *
 * Scenarios:
 * - Given a search result set and hasTodo=true, when the filter changes, then results exclude memos without todos.
 * - Given a search result set and hasAttachment=false, when the filter changes,
 *   then results exclude memos with attachments.
 * - Given active filter flags, when clearSearchFilter is invoked, then the filter becomes inactive.
 *
 * Observable outcomes:
 * - searchResults and searchFilter StateFlow values.
 *
 * TDD proof:
 * - RED before the paging fix because app search delegated to a full-list search flow and applied
 *   filters outside the main-list query port.
 *
 * Excludes:
 * - SearchScreen rendering, MemoUiMapper internals, repository SQL, and debounce/loading timing.
 */
package com.lomo.app.feature.search

import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SearchMemosPageUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelFilterTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()

    private val deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository))
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
        validator = ValidateMemoContentUseCase(),
        resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
        deleteMemoUseCase = deleteMemoUseCase
    )
    private val saveImageUseCase: SaveImageUseCase = mockk()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.resetCallCounts()
        }

        test("default searchFilter is inactive") {
            runTest {
                val viewModel = createViewModel()

                viewModel.searchFilter.value.isActive shouldBe false
            }
        }

        test("hasTodo=true filter excludes memos without a todo marker") {
            runTest {
                val plain = memo(id = "plain", content = "plain text")
                val withTodo = memo(id = "todo", content = "- [ ] check email with text")
                memoRepository.setActiveMemos(listOf(plain, withTodo))

                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("text")
                testDispatcher.scheduler.advanceTimeBy(350)
                viewModel.searchResults.first { it.isNotEmpty() }

                viewModel.onHasTodoChanged(true)
                advanceUntilIdle()

                viewModel.searchResults.value.map { it.id } shouldBe listOf("todo")
                collectJob.cancel()
            }
        }

        test("hasAttachment=false filter keeps only memos with no attachment") {
            runTest {
                val plain = memo(id = "plain", content = "plain memo")
                val withImage = memo(id = "image", content = "![](a.png) memo")
                memoRepository.setActiveMemos(listOf(plain, withImage))

                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.searchResults.collect() }

                viewModel.onSearchQueryChanged("memo")
                testDispatcher.scheduler.advanceTimeBy(350)
                viewModel.searchResults.first { it.isNotEmpty() }

                viewModel.onHasAttachmentChanged(false)
                advanceUntilIdle()

                viewModel.searchResults.value.map { it.id } shouldBe listOf("plain")
                collectJob.cancel()
            }
        }

        test("clearSearchFilter resets all content flags and dates") {
            runTest {
                val viewModel = createViewModel()

                viewModel.onHasTodoChanged(true)
                viewModel.onHasAttachmentChanged(false)
                viewModel.onHasUrlChanged(true)
                advanceUntilIdle()

                viewModel.searchFilter.value.isActive shouldBe true

                viewModel.clearSearchFilter()
                advanceUntilIdle()

                viewModel.searchFilter.value.isActive shouldBe false
            }
        }
    }

    private fun createViewModel(): SearchViewModel =
        SearchViewModel(
            observeActiveDayCountUseCase = observeActiveDayCountUseCase(),
            appConfigStateProvider =
                AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository, com.lomo.app.testing.fakes.FakeCustomFontStore()),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository, com.lomo.app.testing.fakes.FakeCustomFontStore()),
            imageMapProvider = emptyImageMapProvider(),
            projectionMapper = MemoCollectionProjectionMapper(MemoUiMapper()),
            searchMemosPageUseCase = SearchMemosPageUseCase(com.lomo.app.testing.fakes.FakeMemoQueryRepository(memoRepository)),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            toggleMemoCheckboxUseCase = mockk(),
        )

    private fun observeActiveDayCountUseCase(): ObserveActiveDayCountUseCase =
        ObserveActiveDayCountUseCase(
            com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
        )

    private fun memo(
        id: String,
        content: String,
    ): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = content,
            rawContent = content,
            dateKey = "2026_03_24",
        )
}
