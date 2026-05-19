package com.lomo.app.feature.trash

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


import androidx.lifecycle.ViewModel
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoRepository
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Trash list management, batch clear dispatching, permanent item deletions, and restore animations.
 * - Scenarios:
 *   - Given clearTrash call, transition all memos into the deleting state and trigger repository clear operation.
 *   - Given deletePermanently or restoreMemo triggers, retain row deletion indicators until item animations settle.
 * - Observable outcomes:
 *   - deletingMemoIds StateFlow values, trashUiMemos StateFlow lists, and repository call checks.
 * - TDD proof: Asserts timing alignment, collapse marker settlement, and single-batch repo actions.
 * - Excludes: Compose UI trash layouts and pixel details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val createdViewModels = mutableListOf<TrashViewModel>()
    private val repository = FakeMemoRepository()
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
                viewModel.trashUiMemos.first { it.size == 2 }

                viewModel.clearTrash()
                runCurrent()

                viewModel.deletingMemoIds.value shouldBe setOf(firstMemo.id, secondMemo.id)
                repository.clearTrashCallCount shouldBe 1

                viewModel.onDeleteAnimationSettled(firstMemo.id)
                viewModel.onDeleteAnimationSettled(secondMemo.id)
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
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
                viewModel.trashUiMemos.first { it.size == 1 }

                viewModel.deletePermanently(memo)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                finishDelete.complete(Unit)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
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
                viewModel.trashUiMemos.first { it.size == 1 }

                viewModel.restoreMemo(memo)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                finishRestore.complete(Unit)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
            }
        }
    }

    private fun createViewModel(): TrashViewModel =
        TrashViewModel(
            memoUiCoordinator = MemoUiCoordinator(repository),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
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
