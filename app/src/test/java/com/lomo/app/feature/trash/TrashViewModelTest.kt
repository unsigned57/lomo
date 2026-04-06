package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.ThemeMode
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: TrashViewModel
 * - Behavior focus: clear-trash command dispatch plus the rendered-list delete timing during trash restore/delete flows.
 * - Observable outcomes: deleting row ids before execution, one repository clearTrash command instead of per-row permanent deletes, and visible trash rows collapsing when the fade completes instead of leaving transparent gaps while repository work continues.
 * - Red phase: Fails before the fix because trash rows stay structurally present after the fade delay, leaving a long transparent gap while the backing deleted-memo flow waits on repository completion.
 * - Excludes: Compose rendering, animation interpolation, and repository internals.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the trash flows previously asserted that visibleTrashUiMemos becomes
 *   empty as soon as the fade finishes.
 * - Why the previous assertion is no longer correct: removing the row from the rendered list before the backing
 *   repository mutation completes creates a second structural list update that shows up as a rebound in sibling
 *   row motion.
 * - Coverage preserved by: the tests still lock delete/restore timing, deletion markers, and final row removal
 *   after repository completion, while now protecting the single-collapse contract.
 * - Why this is not changing the test to fit the implementation: the revised assertion captures the user-visible
 *   motion requirement rather than an internal implementation choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<TrashViewModel>()

    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var appConfigRepository: com.lomo.domain.repository.AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var memoUiMapper: MemoUiMapper

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

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
        every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
    }

    @After
    fun tearDown() {
        createdViewModels
            .asReversed()
            .forEach(::clearViewModel)
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `clearTrash marks all memos deleting before issuing one batch clear command`() =
        runTest {
            val firstMemo = memo("trash-1", LocalDate.of(2026, 3, 8), 9)
            val secondMemo = memo("trash-2", LocalDate.of(2026, 3, 8), 10)
            every { repository.getDeletedMemosList() } returns MutableStateFlow(listOf(firstMemo, secondMemo))

            val viewModel = createViewModel()
            viewModel.trashUiMemos.first { it.size == 2 }

            viewModel.clearTrash()
            runCurrent()

            assertEquals(setOf(firstMemo.id, secondMemo.id), viewModel.deletingMemoIds.value)
            coVerify(exactly = 0) { repository.clearTrash() }

            advanceTimeBy(300L)
            runCurrent()

            coVerify(exactly = 1) { repository.clearTrash() }
            coVerify(exactly = 0) { repository.deletePermanently(firstMemo) }
            coVerify(exactly = 0) { repository.deletePermanently(secondMemo) }
        }

    @Test
    fun `delete permanently keeps trash row rendered until collapse settles after repository removal`() =
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

            assertEquals(listOf(memo.id), viewModel.visibleTrashUiMemos.value.map { it.memo.id })

            advanceTimeBy(300L)
            runCurrent()

            assertEquals(listOf(memo.id), viewModel.visibleTrashUiMemos.value.map { it.memo.id })
            assertTrue(viewModel.deletingMemoIds.value.contains(memo.id))
            assertTrue(viewModel.collapsingMemoIds.value.contains(memo.id))

            finishDelete.complete(Unit)
            runCurrent()

            assertEquals(listOf(memo.id), viewModel.visibleTrashUiMemos.value.map { it.memo.id })
            assertTrue(viewModel.collapsingMemoIds.value.contains(memo.id))

            advanceTimeBy(220L)
            advanceUntilIdle()

            assertTrue(viewModel.visibleTrashUiMemos.first { it.isEmpty() }.isEmpty())
            assertTrue(viewModel.collapsingMemoIds.value.isEmpty())
        }

    @Test
    fun `restore keeps trash row rendered until collapse settles after repository removal`() =
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

            assertEquals(listOf(memo.id), viewModel.visibleTrashUiMemos.value.map { it.memo.id })

            advanceTimeBy(300L)
            runCurrent()

            assertEquals(listOf(memo.id), viewModel.visibleTrashUiMemos.value.map { it.memo.id })
            assertTrue(viewModel.deletingMemoIds.value.contains(memo.id))
            assertTrue(viewModel.collapsingMemoIds.value.contains(memo.id))

            finishRestore.complete(Unit)
            runCurrent()

            assertEquals(listOf(memo.id), viewModel.visibleTrashUiMemos.value.map { it.memo.id })
            assertTrue(viewModel.collapsingMemoIds.value.contains(memo.id))

            advanceTimeBy(220L)
            advanceUntilIdle()

            assertTrue(viewModel.visibleTrashUiMemos.first { it.isEmpty() }.isEmpty())
            assertTrue(viewModel.collapsingMemoIds.value.isEmpty())
        }

    private fun createViewModel(): TrashViewModel =
        TrashViewModel(
            memoUiCoordinator = MemoUiCoordinator(repository),
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
