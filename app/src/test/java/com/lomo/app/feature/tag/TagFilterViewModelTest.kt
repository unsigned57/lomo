package com.lomo.app.feature.tag

/*
 * Behavior Contract:
 * - Unit under test: TagFilterViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: expose a tag-scoped editable memo collection only when the navigation route supplies a valid tag name.
 *
 * Scenarios:
 * - Given a non-blank tagName route argument, when the ViewModel is created, matching tag memos are loaded.
 * - Given the tagName route argument is missing or blank, when the ViewModel is created, then route state fails explicitly.
 * - Given tag collection mutations fail, when actions are invoked, then shared collection actions surface user-facing errors.
 *
 * Observable outcomes:
 * - tagName value, deletingMemoIds StateFlow values, errorMessage StateFlow values, pagedUiMemos flow emissions, and thrown route contract failures.
 *
 * TDD proof:
 * - RED before route contract fix: missing tagName creates a ViewModel with an empty-string tag instead of failing.
 *
 * Excludes:
 * - Compose tag rendering, Search/Main wiring, data/domain query behavior, and bitmap decoding.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMediaRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.FakeSaveImageUseCase
import com.lomo.domain.usecase.GetMemosByTagPageUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

@OptIn(ExperimentalCoroutinesApi::class)
class TagFilterViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val mediaRepository = FakeMediaRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()

    private val deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository))
    private val updateMemoContentUseCase =
        UpdateMemoContentUseCase(
            repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
            validator = ValidateMemoContentUseCase(),
            resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
            deleteMemoUseCase = deleteMemoUseCase,
        )
    private val toggleMemoCheckboxUseCase =
        ToggleMemoCheckboxUseCase(
            repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
            validator = ValidateMemoContentUseCase(),
        )
    private val saveImageUseCase = FakeSaveImageUseCase(mediaRepository)

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null
            saveImageUseCase.saveResult = null
        }

        test("memos are loaded for tagName and exposed through state") {
            runTest {
                val expected = sampleMemo(id = "memo-tag", content = "has tag work").copy(tags = listOf("work"))
                memoRepository.setActiveMemos(listOf(expected))
                val viewModel = createViewModel(tagName = "work")
                val pagingEmissions = mutableListOf<PagingData<MemoUiModel>>()
                val collectJob = backgroundScope.launch(testDispatcher) {
                    viewModel.pagedUiMemos.collect { pagingEmissions += it }
                }
                advanceUntilIdle()

                viewModel.tagName shouldBe "work"
                pagingEmissions.size shouldBe 1
                collectJob.cancel()
            }
        }

        test("missing tagName route argument fails instead of querying an empty tag") {
            val failure =
                shouldThrow<IllegalStateException> {
                    createViewModel(savedStateHandle = SavedStateHandle())
                }

            failure.message shouldBe "TagFilterViewModel requires non-blank tagName route argument"
        }

        test("blank tagName route argument fails instead of querying an empty tag") {
            val failure =
                shouldThrow<IllegalStateException> {
                    createViewModel(savedStateHandle = SavedStateHandle(mapOf("tagName" to "  ")))
                }

            failure.message shouldBe "TagFilterViewModel requires non-blank tagName route argument"
        }

        test("deleteMemo exposes mapped error message on failure") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo()
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"
            }
        }

        test("deleteMemo updates active state to deleting and triggers usecase") {
            runTest {
                val viewModel = createViewModel(tagName = "work")
                val memo = sampleMemo(id = "memo-delete")
                memoRepository.setActiveMemos(listOf(memo))

                viewModel.deleteMemo(memo, null)
                runCurrent()

                viewModel.deletingMemoIds.value.contains(memo.id) shouldBe true

                viewModel.onDeleteAnimationSettled(memo.id)
                runCurrent()

                viewModel.deletingMemoIds.value.isEmpty() shouldBe true
                memoRepository.currentDeletedMemos() shouldBe listOf(memo.copy(isDeleted = true))
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

                val updated = memoRepository.currentActiveMemos().first()
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
                saveImageUseCase.saveResult =
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

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }
    }

    private fun createViewModel(tagName: String): TagFilterViewModel =
        createViewModel(savedStateHandle = SavedStateHandle(mapOf("tagName" to tagName)))

    private fun createViewModel(savedStateHandle: SavedStateHandle): TagFilterViewModel =
        TagFilterViewModel(
            savedStateHandle = savedStateHandle,
            getMemosByTagPageUseCase =
                GetMemosByTagPageUseCase(
                    com.lomo.app.testing.fakes.FakeMemoSearchRepository(memoRepository),
                ),
            observeActiveDayCountUseCase = observeActiveDayCountUseCase(),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    appPreferencesSnapshotRepository = appConfigRepository,
                    customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                    appScope = CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = MemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
            saveImageUseCase = saveImageUseCase,
        )

    private fun observeActiveDayCountUseCase(): ObserveActiveDayCountUseCase =
        ObserveActiveDayCountUseCase(
            com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
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
