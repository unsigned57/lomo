package com.lomo.app.feature.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMediaRepository
import com.lomo.app.testing.fakes.FakeMemoSearchRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.FakeMemoTrashRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.FakeSaveImageUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoCollectionStateHolder
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: centralize memo collection UI state, image/save/update/toggle actions, deletion animations, and error messages.
 *
 * Scenarios:
 * - Given a tag-like source, when delete/update/toggle/save image actions run, then deleting ids and user-facing errors come from one holder.
 * - Given a trash-like source, when restore/delete/clear actions run, then batch deletion markers persist until animation settlement.
 * - Given source memos and app storage dependencies change, when state is collected, then mapped UI memos are exposed by the same holder.
 *
 * Observable outcomes:
 * - MemoCollectionUiState values, deletingMemoIds flow, errorMessage flow, mapped uiMemos, save image callbacks, and fake repository state.
 *
 * TDD proof:
 * - RED before implementation: MemoCollectionStateHolder, MemoCollectionActions, and MemoCollectionUiState do not exist, proving the shared owner is missing.
 *
 * Excludes:
 * - Compose rendering, Search/Main wiring, data/domain query behavior, and image bitmap decoding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoCollectionStateHolderTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val memoRepository = FakeMemoStore()
    private val memoSearchRepository = FakeMemoSearchRepository(memoRepository)
    private val memoTrashRepository = FakeMemoTrashRepository(memoRepository)
    private val appConfigRepository = FakeAppConfigRepository()
    private val mediaRepository = FakeMediaRepository()
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
    private val createdOwners = mutableListOf<ViewModel>()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            memoRepository.setActiveMemos(emptyList())
            memoRepository.setDeletedMemos(emptyList())
            memoRepository.resetRecordedCalls()
            memoRepository.deleteMemoFailure = null
            memoRepository.updateMemoFailure = null
            memoRepository.deletePermanentlyOverride = null
            memoRepository.restoreMemoOverride = null
            saveImageUseCase.saveResult = null
        }

        afterTest {
            createdOwners
                .asReversed()
                .forEach(::clearViewModel)
            createdOwners.clear()
        }

        test("given tag source when common actions fail then holder owns rollback and errors") {
            runTest {
                val memo = memo(id = "tag-1", content = "- [ ] task").copy(tags = listOf("work"))
                memoRepository.setActiveMemos(listOf(memo))
                val holder = createHolder(source = { memoRepository.observeTaggedMemoPage("work", 50, 0) })
                val collectJob = backgroundScope.launch(testDispatcher) { holder.uiState.collect() }
                holder.memos.first { it == listOf(memo) }
                memoRepository.deleteMemoFailure = IllegalStateException("delete failed")

                holder.actions.delete(memo)
                advanceUntilIdle()

                holder.uiState.value.errorMessage shouldBe "Failed to delete memo: delete failed"
                holder.uiState.value.deletingMemoIds shouldBe emptySet()

                holder.errors.clear()
                holder.actions.toggleTodo(memo = memo, lineIndex = 0, checked = true)
                advanceUntilIdle()

                memoRepository.currentActiveMemos().first().content shouldBe "- [x] task"
                holder.uiState.value.errorMessage shouldBe null

                saveImageUseCase.saveResult =
                    SaveImageResult.SavedButCacheSyncFailed(
                        location = StorageLocation("images/photo.jpg"),
                        cause = IllegalStateException("cache sync failed"),
                )
                var onErrorCalled = false
                val inputUri = mockk<android.net.Uri>()
                every { inputUri.toString() } returns "content://images/photo"

                holder.actions.saveImage(
                    uri = inputUri,
                    onResult = {},
                    onError = { onErrorCalled = true },
                )
                advanceUntilIdle()

                holder.uiState.value.errorMessage shouldBe "Failed to save image: cache sync failed"
                onErrorCalled shouldBe true
                collectJob.cancel()
            }
        }

        test("given trash source when restore delete and clear run then shared holder retains animation markers") {
            runTest {
                val firstMemo = memo(id = "trash-1", content = "trash one")
                val secondMemo = memo(id = "trash-2", content = "trash two")
                memoRepository.setDeletedMemos(listOf(firstMemo, secondMemo))
                val finishRestore = CompletableDeferred<Unit>()
                memoRepository.restoreMemoOverride = {
                    finishRestore.await()
                    memoRepository.setDeletedMemos(listOf(secondMemo))
                }
                val holder =
                    createHolder(
                        source = { memoRepository.observeDeletedMemoPage(50, 0) },
                        capabilities =
                            MemoCollectionCapabilities.Trash(
                                restoreMemo = memoTrashRepository::restoreMemo,
                                deletePermanently = memoTrashRepository::deletePermanently,
                                clearTrash = memoTrashRepository::clearTrash,
                        ),
                    )
                val collectJob = backgroundScope.launch(testDispatcher) { holder.uiState.collect() }
                holder.uiMemos.first { it.size == 2 }

                holder.actions.restore(firstMemo)
                runCurrent()

                holder.uiState.value.deletingMemoIds shouldBe setOf(firstMemo.id)

                finishRestore.complete(Unit)
                runCurrent()

                holder.uiState.value.deletingMemoIds shouldBe setOf(firstMemo.id)

                holder.actions.onDeleteAnimationSettled(firstMemo.id)
                runCurrent()
                holder.uiState.value.deletingMemoIds shouldBe emptySet()

                holder.actions.clearTrash()
                runCurrent()

                holder.uiState.value.deletingMemoIds shouldBe setOf(secondMemo.id)
                memoRepository.clearTrashCallCount shouldBe 1
                collectJob.cancel()
            }
        }
    }

    private fun createHolder(
        source: () -> Flow<List<Memo>>,
        capabilities: MemoCollectionCapabilities =
            MemoCollectionCapabilities.Editable(
                deleteMemo = deleteMemoUseCase::invoke,
                updateMemo = updateMemoContentUseCase::invoke,
                toggleTodo = { memo, lineIndex, checked ->
                    toggleMemoCheckboxUseCase(memo = memo, lineIndex = lineIndex, checked = checked)
                },
                saveImage = saveImageUseCase::saveWithCacheSyncStatus,
            ),
    ): MemoCollectionStateHolder {
        val owner = TestViewModel()
        createdOwners += owner
        return MemoCollectionStateHolder(
            source = source(),
            configStateProvider =
                AppConfigStateProvider(
                    AppConfigUiCoordinator(appConfigRepository, com.lomo.app.testing.fakes.FakeCustomFontStore()),
                    CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            imageMapProvider = emptyImageMapProvider(),
            memoUiMapper = MemoUiMapper(),
            capabilities = capabilities,
            scope = owner.viewModelScope,
        )
    }

    private fun clearViewModel(viewModel: ViewModel) {
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(viewModel)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private class TestViewModel : ViewModel()

    private fun memo(
        id: String,
        content: String,
    ): Memo {
        val date = LocalDate.of(2026, 3, 24)
        return Memo(
            id = id,
            timestamp =
                date
                    .atTime(10, 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = "2026_03_24",
            localDate = date,
        )
    }
}
