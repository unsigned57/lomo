package com.lomo.app.feature.trash

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.ThemeMode
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: TrashViewModel
 * - Behavior focus: clear-trash command dispatch and delete animation state during batch trash cleanup.
 * - Observable outcomes: deleting row ids before execution and one repository clearTrash command instead of per-row permanent deletes.
 * - Red phase: Fails before the fix because clearTrash loops through deletePermanently per memo instead of issuing one batch clear-trash mutation.
 * - Excludes: Compose rendering, animation visuals, and repository internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var appConfigRepository: com.lomo.domain.repository.AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var memoUiMapper: MemoUiMapper

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        repository = mockk(relaxed = true)
        appConfigRepository = mockk(relaxed = true)
        imageMapProvider = mockk(relaxed = true)
        memoUiMapper = MemoUiMapper()

        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())
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

    private fun createViewModel(): TrashViewModel =
        TrashViewModel(
            memoUiCoordinator = MemoUiCoordinator(repository),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = memoUiMapper,
        )

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
