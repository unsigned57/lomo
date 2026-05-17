package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: TagFilterViewModel
 * - Behavior focus: tag-scoped memo propagation, todo checkbox mutation, and mutation/save error handling.
 * - Observable outcomes: tagName usage, memos state values, error message state, use-case calls,
 *   and callback side effects.
 * - Red phase: Fails before the fix because TagFilterViewModel has no todo toggle entrypoint and
 *   does not inject ToggleMemoCheckboxUseCase.
 * - Excludes: Compose rendering, mapper internals, and repository implementation details.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression coverage extension.
 * - Old behavior/assertion being replaced: the file-level contract described tag mutation tests as
 *   test-only metadata alignment with no production change.
 * - Why old assertion is no longer correct: this file now adds independent todo toggle regression
 *   scenarios that require production behavior in TagFilterViewModel.
 * - Coverage preserved by: existing tag list, delete, update, image-save, and clear-error tests stay unchanged.
 * - Why this is not fitting the test to the implementation: the new assertions encode the reported
 *   tag-detail todo checkbox behavior expected to match the main memo list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagFilterViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }

    private lateinit var memoRepository: MemoRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var deleteMemoUseCase: DeleteMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase

    init {
        beforeTest {
memoRepository = mockk(relaxed = true)
            appConfigRepository = mockk(relaxed = true)
            imageMapProvider = emptyImageMapProvider()
            deleteMemoUseCase = mockk(relaxed = true)
            updateMemoContentUseCase = mockk(relaxed = true)
            toggleMemoCheckboxUseCase = mockk(relaxed = true)
            saveImageUseCase = mockk(relaxed = true)

            every { memoRepository.getMemosByTagList(any()) } returns flowOf(emptyList())
            every { memoRepository.getActiveDayCount() } returns flowOf(0)

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

            coEvery { deleteMemoUseCase(any()) } returns Unit
            coEvery { updateMemoContentUseCase(any(), any()) } returns Unit
            coEvery { toggleMemoCheckboxUseCase(any(), any(), any()) } returns true
            coEvery { saveImageUseCase.saveWithCacheSyncStatus(any()) } returns
                SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
        }
    }

    init {
        test("memos are loaded for tagName and exposed through state") {
            runTest {
                val expected = sampleMemo(id = "memo-tag")
                every { memoRepository.getMemosByTagList("work") } returns flowOf(listOf(expected))
                val viewModel = createViewModel(tagName = "work")
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.memos.collect() }

                val memos = viewModel.memos.first { it.isNotEmpty() }

                (viewModel.tagName) shouldBe ("work")
                (memos) shouldBe (listOf(expected))
                verify(exactly = 1) { memoRepository.getMemosByTagList("work") }
                collectJob.cancel()
            }
        }
    }

    init {
        test("deleteMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

                viewModel.deleteMemo(sampleMemo())
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to delete memo: delete failed")
            }
        }
    }

    init {
        test("deleteMemo keeps deleting id until tag list animation settles") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-delete")

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)

                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
            }
        }
    }

    init {
        test("updateMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-update")
                coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to update memo: update failed")
            }
        }
    }

    init {
        test("toggleTodo delegates checkbox toggle use case") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-todo", content = "- [ ] task")

                viewModel.toggleTodo(memo = memo, lineIndex = 0, checked = true)
                advanceUntilIdle()

                coVerify(exactly = 1) {
                    toggleMemoCheckboxUseCase(memo = memo, lineIndex = 0, checked = true)
                }
                (viewModel.errorMessage.value) shouldBe null
            }
        }
    }

    init {
        test("toggleTodo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-todo", content = "- [ ] task")
                coEvery {
                    toggleMemoCheckboxUseCase(memo = memo, lineIndex = 0, checked = true)
                } throws IllegalStateException("toggle failed")

                viewModel.toggleTodo(memo = memo, lineIndex = 0, checked = true)
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to update todo: toggle failed")
            }
        }
    }

    init {
        test("saveImage cache sync failure reports error and invokes onError") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val inputUri = mockk<android.net.Uri>()
                every { inputUri.toString() } returns "content://images/photo-2"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://images/photo-2"))
                } returns
                    SaveImageResult.SavedButCacheSyncFailed(
                        location = StorageLocation("images/photo-2.jpg"),
                        cause = IllegalStateException("cache sync failed"),
                    )
                var savedPath: String? = null
                var onErrorCalled = false

                viewModel.saveImage(
                    uri = inputUri,
                    onResult = { path -> savedPath = path },
                    onError = { onErrorCalled = true },
                )
                advanceUntilIdle()

                (savedPath) shouldBe null
                (viewModel.errorMessage.value) shouldBe ("Failed to save image: cache sync failed")
                ((onErrorCalled)) shouldBe true
            }
        }
    }

    init {
        test("clearError clears existing error message") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                coEvery { deleteMemoUseCase(any()) } throws IllegalStateException("delete failed")

                viewModel.deleteMemo(sampleMemo())
                advanceUntilIdle()
                (viewModel.errorMessage.value) shouldBe ("Failed to delete memo: delete failed")

                viewModel.clearError()

                (viewModel.errorMessage.value) shouldBe null
            }
        }
    }

    private fun createViewModel(tagName: String): TagFilterViewModel =
        TagFilterViewModel(
            savedStateHandle = SavedStateHandle(mapOf("tagName" to tagName)),
            memoUiCoordinator = MemoUiCoordinator(memoRepository),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = MemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
            saveImageUseCase = saveImageUseCase,
        )

    private fun sampleMemo(
        id: String = "memo-0",
        content: String = "content",
    ): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = "2026_03_24",
        )
}
