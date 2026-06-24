package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoTrashUseCase
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: TrashViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Trash list management, batch clear dispatching, permanent item deletions, and restore animations.
 *
 * Scenarios:
 * - Given clearTrash is called with multiple memos, when the operation runs, then all memos transition into the deleting state and one batch clear command is issued to the repository.
 * - Given deletePermanently is triggered for a memo, when the repository completes removal, then the row deletion indicator is retained until the trash animation settles.
 * - Given restoreMemo is triggered for a memo, when the repository completes the restore, then the row deletion indicator is retained until the trash animation settles.
 *
 * Observable outcomes:
 * - deletingMemoIds StateFlow values, pagedUiMemos flow emissions, recorded page calls, and repository call checks.
 *
 * TDD proof:
 * - Asserts timing alignment, collapse marker settlement, and single-batch repo actions were not present before the LomoList exit animation contract.
 *
 * Excludes:
 * - Compose UI trash layouts and pixel details.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val createdViewModels = mutableListOf<TrashViewModel>()
    private val repository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()
    private val memoUiMapper: MemoUiMapper = MemoUiMapper()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            repository.setActiveMemos(emptyList())
            repository.setDeletedMemos(emptyList())
            repository.resetCallCounts()
            repository.deletePermanentlyOverride = null
            repository.restoreMemoOverride = null
        }

        afterTest {
            createdViewModels
                .asReversed()
                .forEach(::clearViewModel)
            createdViewModels.clear()
        }

        test("clearTrash marks all memos deleting before issuing one batch clear command") {
            runTest {
                val firstMemo = memo("trash-1", LocalDate.of(2026, 3, 8), 9)
                val secondMemo = memo("trash-2", LocalDate.of(2026, 3, 8), 10)
                repository.setDeletedMemos(listOf(firstMemo, secondMemo))

                val viewModel = createViewModel()
                val pagingEmissions = mutableListOf<PagingData<MemoUiModel>>()
                val collectJob = backgroundScope.launch {
                    viewModel.pagedUiMemos.collect { pagingEmissions += it }
                }
                advanceUntilIdle()

                viewModel.clearTrash(
                    listOf(
                        Triple(firstMemo.id, firstMemo, null),
                        Triple(secondMemo.id, secondMemo, null)
                    )
                )
                runCurrent()

                viewModel.deletingMemoIds.value shouldBe setOf(firstMemo.id, secondMemo.id)
                repository.clearTrashCallCount shouldBe 1

                viewModel.onDeleteAnimationSettled(firstMemo.id)
                viewModel.onDeleteAnimationSettled(secondMemo.id)
                runCurrent()
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                collectJob.cancel()
            }
        }

        test("delete permanently keeps collapse marker until trash animation settles after repository removal") {
            runTest {
                val memo = memo("trash-delete", LocalDate.of(2026, 3, 8), 9)
                repository.setDeletedMemos(listOf(memo))
                val finishDelete = CompletableDeferred<Unit>()
                repository.deletePermanentlyOverride = { _ ->
                    finishDelete.await()
                    repository.setDeletedMemos(emptyList())
                }

                val viewModel = createViewModel()
                val pagingEmissions = mutableListOf<PagingData<MemoUiModel>>()
                val collectJob = backgroundScope.launch {
                    viewModel.pagedUiMemos.collect { pagingEmissions += it }
                }
                advanceUntilIdle()

                viewModel.deletePermanently(memo, null)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                finishDelete.complete(Unit)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                runCurrent()
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                collectJob.cancel()
            }
        }

        test("restore keeps collapse marker until trash animation settles after repository removal") {
            runTest {
                val memo = memo("trash-restore", LocalDate.of(2026, 3, 8), 9)
                repository.setDeletedMemos(listOf(memo))
                val finishRestore = CompletableDeferred<Unit>()
                repository.restoreMemoOverride = { _ ->
                    finishRestore.await()
                    repository.setDeletedMemos(emptyList())
                }

                val viewModel = createViewModel()
                val pagingEmissions = mutableListOf<PagingData<MemoUiModel>>()
                val collectJob = backgroundScope.launch {
                    viewModel.pagedUiMemos.collect { pagingEmissions += it }
                }
                advanceUntilIdle()

                viewModel.restoreMemo(memo, null)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                finishRestore.complete(Unit)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                runCurrent()
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                collectJob.cancel()
            }
        }
    }

    private fun createViewModel(): TrashViewModel =
        TrashViewModel(
            memoTrashUseCase =
                MemoTrashUseCase(
                    com.lomo.app.testing.fakes.FakeMemoTrashRepository(repository),
                ),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    appPreferencesSnapshotRepository = appConfigRepository,
                    customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                    appScope = CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            imageMapProvider = imageMapProvider,
            memoUiMapper = memoUiMapper,
        ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: TrashViewModel) {
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(viewModel)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun memo(
        id: String,
        date: LocalDate,
        hour: Int,
    ): Memo =
        Memo(
            id = id,
            timestamp =
                date
                    .atTime(hour, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            content = id,
            rawContent = id,
            dateKey = date.toString().replace("-", "_"),
            localDate = date,
        )
}
