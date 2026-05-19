/*
 * Test Contract:
 * - Unit under test: SearchViewModel filter wiring (orthogonal to debounce/loading behavior covered in SearchViewModelTest).
 * - Behavior focus: applying MemoListFilter content flags to FTS results; mutators update filter state; result stream re-emits on filter change.
 * - Observable outcomes: searchResults StateFlow values, searchFilter StateFlow values.
 * - Red phase: Fails before SearchViewModel exposes searchFilter / content flag mutators or pipes results through ApplyMainMemoFilterUseCase.
 * - Excludes: SearchScreen UI rendering, MemoUiMapper internals.
 */
package com.lomo.app.feature.search

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


import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
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
    private val memoRepository = FakeMemoRepository()
    private val appConfigRepository = FakeAppConfigRepository()

    private val deleteMemoUseCase = DeleteMemoUseCase(memoRepository)
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = memoRepository,
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
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigStateProvider =
                AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = emptyImageMapProvider(),
            memoUiMapper = MemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
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
