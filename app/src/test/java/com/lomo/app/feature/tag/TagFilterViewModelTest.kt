package com.lomo.app.feature.tag

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


import androidx.lifecycle.SavedStateHandle
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
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Tag-filtered memo retrieval, todo item check updates, update/delete modifications, and error surfacing.
 * - Scenarios:
 *   - Given active tag name in route, fetch and map only memos with matching tag values.
 *   - Given checkbox toggle updates, execute ToggleMemoCheckboxUseCase and map errors cleanly.
 *   - Given saveImage or delete mutations fail, expose user-facing localized error messages.
 * - Observable outcomes:
 *   - tagName value, memos Flow list, deletingMemoIds Flow list, and errorMessage Flow states.
 * - TDD proof: Asserts correct UI event propagation, use case invocations, and flow timing.
 * - Excludes: Compose tag rendering layouts and view components.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagFilterViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoRepository()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()

    // Real UseCases built on FakeMemoRepository for a true Fake-First integration
    private val deleteMemoUseCase = DeleteMemoUseCase(memoRepository)
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = memoRepository,
        validator = ValidateMemoContentUseCase(),
        resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
        deleteMemoUseCase = deleteMemoUseCase
    )
    private val toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(
        repository = memoRepository,
        validator = ValidateMemoContentUseCase()
    )
    private val saveImageUseCase: SaveImageUseCase = mockk()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            clearMocks(saveImageUseCase)
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null
        }

        test("memos are loaded for tagName and exposed through state") {
            runTest {
                val expected = sampleMemo(id = "memo-tag", content = "has tag work").copy(tags = listOf("work"))
                memoRepository.setActiveMemos(listOf(expected))
                val viewModel = createViewModel(tagName = "work")
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.memos.collect() }

                val memos = viewModel.memos.first { it.isNotEmpty() }

                viewModel.tagName shouldBe "work"
                memos shouldBe listOf(expected)
                collectJob.cancel()
            }
        }

        test("deleteMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo()
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                viewModel.deleteMemo(memo)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"
            }
        }

        test("deleteMemo updates active state to deleting and triggers usecase") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-delete")
                memoRepository.setActiveMemos(listOf(memo))

                viewModel.deleteMemo(memo)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)

                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                memoRepository.getDeletedMemosList().first() shouldBe listOf(memo.copy(isDeleted = true))
            }
        }

        test("updateMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-update")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("update failed")

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update memo: update failed"
            }
        }

        test("toggleTodo delegates checkbox toggle use case") {
            runTest {
                val memo = sampleMemo(id = "memo-todo", content = "- [ ] task")
                memoRepository.setActiveMemos(listOf(memo))
                val viewModel = createViewModel(tagName = "work")

                viewModel.toggleTodo(memo = memo, lineIndex = 0, checked = true)
                advanceUntilIdle()

                val updated = memoRepository.getAllMemosList().first().first()
                updated.content shouldBe "- [x] task"
                viewModel.errorMessage.value shouldBe null
            }
        }

        test("toggleTodo exposes mapped error message on failure") {
            runTest {
                val memo = sampleMemo(id = "memo-todo", content = "- [ ] task")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("toggle failed")
                val viewModel = createViewModel(tagName = "work")

                viewModel.toggleTodo(memo = memo, lineIndex = 0, checked = true)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update todo: toggle failed"
            }
        }

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

                savedPath shouldBe null
                viewModel.errorMessage.value shouldBe "Failed to save image: cache sync failed"
                onErrorCalled shouldBe true
            }
        }

        test("clearError clears existing error message") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo()
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                viewModel.deleteMemo(memo)
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
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
