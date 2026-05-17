package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.ThemeMode
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: TrashViewModel
 * - Behavior focus: clear-trash command dispatch plus delete/collapse id timing during trash restore/delete flows.
 * - Observable outcomes: deleting row ids before execution, one repository clearTrash command instead of per-row permanent deletes, and collapse markers that stay active until the composable-side retention callback settles.
 * - Red phase: Fails before the fix because TrashViewModel owned a separate visible list tracker, so trash retention behavior could not be driven by the shared composable-side settle contract.
 * - Excludes: Compose rendering, animation interpolation, and repository internals.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the trash flows previously asserted against a ViewModel-owned rendered
 *   list (`visibleTrashUiMemos`) that disappeared once tracker cleanup ran.
 * - Why the previous assertion is no longer correct: Trash now follows the same composable-side retained-row model
 *   as the main list, so the ViewModel contract is to hold delete/collapse ids until the UI reports animation settle.
 * - Coverage preserved by: the tests still lock delete/restore timing, deletion markers, and final cleanup
 *   after repository completion, while now protecting the shared settle callback contract.
 * - Why this is not changing the test to fit the implementation: the revised assertion captures the user-visible
 *   motion requirement rather than an internal implementation choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }
    private val createdViewModels = mutableListOf<TrashViewModel>()

    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var appConfigRepository: com.lomo.domain.repository.AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var memoUiMapper: MemoUiMapper

    init {
        beforeTest {
repository = mockk(relaxed = true)
            appConfigRepository = mockk(relaxed = true)
            imageMapProvider = emptyImageMapProvider()
            memoUiMapper = MemoUiMapper()

            every { repository.getDeletedMemosList() } returns MutableStateFlow(emptyList())
            every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(null)
            every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns flowOf(null)
            every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
            every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
            every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
            every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
            every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
            every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
            every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(false)
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.getMemoActionOrder() } returns flowOf(emptyList())
            every { appConfigRepository.getMemoActionOrdersByScope() } returns flowOf(emptyMap())
            every { appConfigRepository.getInputToolbarToolOrder() } returns flowOf(emptyList())
            every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
            every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
        }
    }

    init {
        afterTest {
            createdViewModels
                .asReversed()
                .forEach(::clearViewModel)
            createdViewModels.clear()
}
    }

    init {
        test("clearTrash marks all memos deleting before issuing one batch clear command") {
            runTest {
                val firstMemo = memo("trash-1", LocalDate.of(2026, 3, 8), 9)
                val secondMemo = memo("trash-2", LocalDate.of(2026, 3, 8), 10)
                every { repository.getDeletedMemosList() } returns MutableStateFlow(listOf(firstMemo, secondMemo))

                val viewModel = createViewModel()
                viewModel.trashUiMemos.first { it.size == 2 }

                viewModel.clearTrash()
                runCurrent()

                (viewModel.deletingMemoIds.value) shouldBe (setOf(firstMemo.id, secondMemo.id))
                coVerify(exactly = 1) { repository.clearTrash() }

                viewModel.onDeleteAnimationSettled(firstMemo.id)
                viewModel.onDeleteAnimationSettled(secondMemo.id)
                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
            }
        }
    }

    init {
        test("delete permanently keeps collapse marker until trash animation settles after repository removal") {
            runTest {
                val memo = memo("trash-delete", LocalDate.of(2026, 3, 8), 9)
                val deletedMemosFlow = MutableStateFlow(listOf(memo))
                val finishDelete = CompletableDeferred<Unit>()
                every { repository.getDeletedMemosList() } returns deletedMemosFlow
                io.mockk.coEvery { repository.deletePermanently(memo) } coAnswers {
                    finishDelete.await()
                    deletedMemosFlow.value = emptyList()
                }

                val viewModel = createViewModel()
                viewModel.trashUiMemos.first { it.size == 1 }

                viewModel.deletePermanently(memo)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                finishDelete.complete(Unit)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
            }
        }
    }

    init {
        test("restore keeps collapse marker until trash animation settles after repository removal") {
            runTest {
                val memo = memo("trash-restore", LocalDate.of(2026, 3, 8), 9)
                val deletedMemosFlow = MutableStateFlow(listOf(memo))
                val finishRestore = CompletableDeferred<Unit>()
                every { repository.getDeletedMemosList() } returns deletedMemosFlow
                io.mockk.coEvery { repository.restoreMemo(memo) } coAnswers {
                    finishRestore.await()
                    deletedMemosFlow.value = emptyList()
                }

                val viewModel = createViewModel()
                viewModel.trashUiMemos.first { it.size == 1 }

                viewModel.restoreMemo(memo)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                finishRestore.complete(Unit)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
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
