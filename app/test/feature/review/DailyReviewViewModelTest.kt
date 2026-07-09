package com.lomo.app.feature.review

/**
 * Behavior Contract:
 * - Unit under test: DailyReviewViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: daily review screen state, memo mutations, image saves, and incremental random-walk loading.
 *
 * Scenarios:
 * - Given no review memos, when the screen loads, then success is published with an empty list.
 * - Given review query failure, when the screen loads, then a user-visible UI error is published.
 * - Given a visible review memo, when update or delete succeeds, then the current visible list is mutated in place.
 * - Given image save cache sync fails, when saving from review, then error state and callback both report failure.
 * - Given more unseen memos exist, when loading more, then new review memos append without dropping current ones.
 * - Given a seen review memo is deleted and the backing collection changes before the frozen snapshot is exhausted,
 *   when loading more in the same session, then only the original unseen snapshot ids append and the deleted seen id stays excluded.
 * - Given loadMore is in flight and a visible memo deletion completes before the page returns, when the page appends,
 *   then it merges with the latest visible list and does not resurrect the deleted memo.
 * - Given a visible review memo with a markdown checkbox, when its todo is toggled, then the persisted store and
 *   the emitted snapshot both reflect the new checkbox state (the review list is a frozen snapshot, so it cannot
 *   rely on a live re-query).
 * - Given a todo toggle that fails to persist, when toggling, then a user-visible error is published and the
 *   displayed content stays unchanged.
 *
 * Observable outcomes:
 * - UiState, errorMessage, memo ids/content, callbacks, and fake repository mutation counters.
 *
 * TDD proof:
 * - Fails in qualityCheck when update success expected repository insertion order instead of current review order.
 * - RED command: `./kotlin test --include-classes='com.lomo.app.feature.review.DailyReviewViewModelTest'`.
 * - RED symptom: the strengthened loadMore tests failed with assertion diffs at `DailyReviewViewModelTest.kt:298` and `DailyReviewViewModelTest.kt:334`.
 * - RED symptom: the in-flight loadMore merge test resurrects the deleted memo because loadMore writes a captured old list.
 * - RED for the todo toggle: with the optimistic `rawMemos` update removed from `toggleTodo`, only the persisted
 *   store changes while the emitted snapshot keeps the old checkbox, so the "reflects in emitted state" assertion fails.
 * - GREEN command: the same targeted app command passes after the domain source carries frozen candidate ids and the app test asserts the expected snapshot remainder.
 *
 * Excludes:
 * - Compose rendering, repository internals, and the random permutation algorithm itself.
 *
 * Test Change Justification:
 * - Reason category: assertion contract correction after random-walk review order surfaced in qualityCheck.
 * - Old behavior/assertion being replaced: update success expected repository insertion order.
 * - Why old assertion is no longer correct: daily review displays a seeded random-walk order.
 * - Coverage preserved by: asserting the current visible order is unchanged while the updated content appears.
 * - Why this is not fitting the test to the implementation: the test name promises no full reload, not input-order sorting.
 */


import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeDailyReviewSessionRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.FakeDispatcherProvider
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider: ImageMapProvider = emptyImageMapProvider()

    private val deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository))
    private val updateMemoContentUseCase = UpdateMemoContentUseCase(
        repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
        validator = ValidateMemoContentUseCase(),
        resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
        deleteMemoUseCase = deleteMemoUseCase,
    )
    private val dailyReviewSessionRepository = FakeDailyReviewSessionRepository()
    private val dailyReviewSessionUseCase = DailyReviewSessionUseCase(dailyReviewSessionRepository)
    private val dailyReviewQueryUseCase = DailyReviewQueryUseCase(com.lomo.app.testing.fakes.FakeMemoQueryRepository(memoRepository))
    private val toggleMemoCheckboxUseCase = com.lomo.domain.usecase.ToggleMemoCheckboxUseCase(
        repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
        validator = ValidateMemoContentUseCase(),
    )
    private val saveImageUseCase: SaveImageUseCase = mockk()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            clearMocks(saveImageUseCase)
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.resetCallCounts()
            memoRepository.getMemoCountFailure = null
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null

            dailyReviewSessionRepository.session = DailyReviewSession(
                date = LocalDate.of(2026, 4, 16),
                seed = 1L,
                pageIndex = 0,
            )
        }

        test("initial load with empty result publishes success with empty list") {
            runTest {
                val viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                (state is UiState.Success) shouldBe true
                (state as UiState.Success).data shouldBe emptyList<com.lomo.app.feature.main.MemoUiModel>()
            }
        }

        test("initial load failure publishes ui error") {
            runTest {
                memoRepository.getMemoCountFailure = IllegalStateException("query failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                (state is UiState.Error) shouldBe true
                state as UiState.Error
                state.message shouldBe "Failed to load daily review"
                (state.throwable is IllegalStateException) shouldBe true
            }
        }

        test("updateMemo failure maps to user-facing error") {
            runTest {
                val memo = sampleMemo(id = "memo-update")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("update failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update memo: update failed"
            }
        }

        test("updateMemo success keeps current list without triggering full reload") {
            runTest {
                val memo = sampleMemo(id = "memo-update-success", content = "before")
                val keep = sampleMemo(id = "memo-keep", content = "stable")
                memoRepository.setActiveMemos(listOf(memo, keep))

                val viewModel = createViewModel()
                advanceUntilIdle()
                val visibleIdsBeforeUpdate =
                    (viewModel.uiState.value as UiState.Success)
                        .data
                        .map { it.memo.id }

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                val reloadedState = viewModel.uiState.value as UiState.Success
                reloadedState.data.map { it.memo.id } shouldBe visibleIdsBeforeUpdate
                reloadedState.data.find { it.memo.id == memo.id }?.memo?.content shouldBe "updated"
                viewModel.errorMessage.value shouldBe null
                memoRepository.updateMemoCallCount shouldBe 1
            }
        }

        test("deleteMemo failure maps to user-facing error") {
            runTest {
                val memo = sampleMemo(id = "memo-delete")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to delete memo: delete failed"
            }
        }

        test("deleteMemo success removes memo in place without full reload") {
            runTest {
                val memo = sampleMemo(id = "memo-delete-success")
                memoRepository.setActiveMemos(listOf(memo, sampleMemo("memo-keep")))

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.deleteMemo(memo, null)
                advanceUntilIdle()

                val state = viewModel.uiState.value as UiState.Success
                state.data.map { it.memo.id } shouldBe listOf("memo-keep")
                viewModel.errorMessage.value shouldBe null
                memoRepository.deleteMemoCallCount shouldBe 1
            }
        }

        test("toggleTodo reflects the toggled checkbox in emitted state and persists it") {
            runTest {
                val memo = sampleMemo(id = "memo-todo", content = "- [ ] task")
                memoRepository.setActiveMemos(listOf(memo))
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.toggleTodo(memo, 0, true)
                advanceUntilIdle()

                val state = viewModel.uiState.value
                (state is UiState.Success) shouldBe true
                state as UiState.Success
                state.data.single { it.memo.id == memo.id }.memo.content shouldBe "- [x] task"
                memoRepository.currentActiveMemos().single().content shouldBe "- [x] task"
                viewModel.errorMessage.value shouldBe null
            }
        }

        test("toggleTodo failure maps to user-facing error and leaves displayed content unchanged") {
            runTest {
                val memo = sampleMemo(id = "memo-todo-fail", content = "- [ ] task")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("database lock")
                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.toggleTodo(memo, 0, true)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update todo: database lock"
                val state = viewModel.uiState.value
                (state is UiState.Success) shouldBe true
                state as UiState.Success
                state.data.single { it.memo.id == memo.id }.memo.content shouldBe "- [ ] task"
            }
        }

        test("saveImage success returns saved path and keeps error null") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://review/image-1"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://review/image-1"))
                } returns SaveImageResult.SavedAndCacheSynced(StorageLocation("images/review-1.jpg"))
                var resultPath: String? = null
                var errorCallbackCalled = false

                viewModel.saveImage(
                    uri = uri,
                    onResult = { path -> resultPath = path },
                    onError = { errorCallbackCalled = true },
                )
                advanceUntilIdle()

                resultPath shouldBe "images/review-1.jpg"
                viewModel.errorMessage.value shouldBe null
                errorCallbackCalled shouldBe false
            }
        }

        test("saveImage cache sync failure maps error and triggers onError callback") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://review/image-2"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://review/image-2"))
                } returns
                    SaveImageResult.SavedButCacheSyncFailed(
                        location = StorageLocation("images/review-2.jpg"),
                        cause = IllegalStateException("cache sync failed"),
                    )
                var resultPath: String? = null
                var errorCallbackCalled = false

                viewModel.saveImage(
                    uri = uri,
                    onResult = { path -> resultPath = path },
                    onError = { errorCallbackCalled = true },
                )
                advanceUntilIdle()

                resultPath shouldBe null
                viewModel.errorMessage.value shouldBe "Failed to save image: cache sync failed"
                errorCallbackCalled shouldBe true
            }
        }

        test("clearError clears existing error message") {
            runTest {
                val memo = sampleMemo("memo-clear-error")
                memoRepository.setActiveMemos(listOf(memo))
                memoRepository.updateMemoFailure = IllegalStateException("update failed")

                val viewModel = createViewModel()
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to update memo: update failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }

        test("loadMore appends new memos to the current random walk list") {
            runTest {
                val firstBatch = listOf(sampleMemo("memo-1", "first"), sampleMemo("memo-2", "second"))
                val secondBatch = listOf(sampleMemo("memo-3", "third"))

                memoRepository.setActiveMemos(firstBatch)
                val viewModel = createViewModel()
                advanceUntilIdle()
                val visibleIdsBeforeLoadMore =
                    (viewModel.uiState.value as UiState.Success)
                        .data
                        .map { it.memo.id }

                memoRepository.setActiveMemos(firstBatch + secondBatch)
                viewModel.loadMore()
                advanceUntilIdle()

                val state = viewModel.uiState.value as UiState.Success
                state.data.map { it.memo.id } shouldBe visibleIdsBeforeLoadMore + "memo-3"
            }
        }

        test("loadMore appends only frozen unseen ids after a seen memo is deleted and the backing collection changes") {
            runTest {
                val memos = (0 until 40).map { index -> sampleMemo("memo-$index", "content-$index") }
                val newlyInsertedMemo = sampleMemo("memo-new", "new-arrival")
                memoRepository.setActiveMemos(memos)
                dailyReviewSessionRepository.session =
                    DailyReviewSession(
                        date = LocalDate.now(),
                        seed = 42L,
                        pageIndex = 0,
                    )
                val viewModel = createViewModel()
                advanceUntilIdle()
                val firstState = viewModel.uiState.value as UiState.Success
                val deletedMemo = firstState.data.first().memo
                val visibleIdsBeforeDelete = firstState.data.map { it.memo.id }
                val expectedAppendedIds =
                    memos.map(Memo::id).filterNot { id -> id in visibleIdsBeforeDelete }

                viewModel.deleteMemo(deletedMemo, null)
                advanceUntilIdle()
                val visibleIdsAfterDelete =
                    (viewModel.uiState.value as UiState.Success)
                        .data
                        .map { it.memo.id }
                memoRepository.setActiveMemos(listOf(newlyInsertedMemo) + memos)

                viewModel.loadMore()
                advanceUntilIdle()

                val finalState = viewModel.uiState.value as UiState.Success
                val finalIds = finalState.data.map { it.memo.id }
                expectedAppendedIds.isNotEmpty() shouldBe true
                finalIds.take(visibleIdsAfterDelete.size) shouldBe visibleIdsAfterDelete
                (finalIds.size > visibleIdsAfterDelete.size) shouldBe true
                finalIds.drop(visibleIdsAfterDelete.size).all { id -> id in memos.map(Memo::id) } shouldBe true
                finalIds.distinct().size shouldBe finalIds.size
                finalIds.contains(deletedMemo.id) shouldBe false
                finalIds.contains(newlyInsertedMemo.id) shouldBe false
            }
        }

        test("loadMore appends into latest visible list when deletion completes while page is in flight") {
            runTest {
                val memos = (0 until 21).map { index -> sampleMemo("memo-$index", "content-$index") }
                memoRepository.setActiveMemos(memos)
                dailyReviewSessionRepository.session =
                    DailyReviewSession(
                        date = LocalDate.of(2026, 4, 16),
                        seed = 42L,
                        pageIndex = 0,
                    )
                val viewModel = createViewModel()
                advanceUntilIdle()
                val firstState = viewModel.uiState.value as UiState.Success
                val deletedMemo = firstState.data.first().memo
                val visibleIdsBeforeDelete = firstState.data.map { it.memo.id }
                val expectedAppendedIds =
                    memos.map(Memo::id).filterNot { id -> id in visibleIdsBeforeDelete }
                val loadMoreBlocked = CompletableDeferred<Unit>()
                val continueLoadMore = CompletableDeferred<Unit>()
                var blocked = false
                memoRepository.beforeGetMemoById = {
                    if (!blocked) {
                        blocked = true
                        loadMoreBlocked.complete(Unit)
                        continueLoadMore.await()
                    }
                }

                viewModel.loadMore()
                advanceUntilIdle()
                loadMoreBlocked.isCompleted shouldBe true

                viewModel.deleteMemo(deletedMemo, null)
                advanceUntilIdle()
                val visibleIdsAfterDelete =
                    (viewModel.uiState.value as UiState.Success)
                        .data
                        .map { it.memo.id }
                continueLoadMore.complete(Unit)
                advanceUntilIdle()

                val finalState = viewModel.uiState.value as UiState.Success
                val finalIds = finalState.data.map { it.memo.id }
                expectedAppendedIds.isNotEmpty() shouldBe true
                finalIds shouldBe visibleIdsAfterDelete + expectedAppendedIds
                finalIds.distinct().size shouldBe finalIds.size
                finalIds.contains(deletedMemo.id) shouldBe false
            }
        }
    }

    private fun createViewModel(): DailyReviewViewModel =
        DailyReviewViewModel(
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
            memoUiMapper = MemoUiMapper(FakeDispatcherProvider(testDispatcher)),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
            saveImageUseCase = saveImageUseCase,
            dailyReviewQueryUseCase = dailyReviewQueryUseCase,
            dailyReviewSessionUseCase = dailyReviewSessionUseCase,
        )

    private fun observeActiveDayCountUseCase(): ObserveActiveDayCountUseCase =
        ObserveActiveDayCountUseCase(
            com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
        )

    private fun sampleMemo(
        id: String,
        content: String = "memo content",
    ): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = "2026_03_24",
        )
}
