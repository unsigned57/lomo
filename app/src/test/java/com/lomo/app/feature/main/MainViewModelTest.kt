package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.app.BuildConfig
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.media.AudioPlayerManager
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.FakeImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeAppVersionRepository
import com.lomo.app.testing.fakes.FakeMemoVersionRepository
import com.lomo.app.testing.fakes.FakeAppWidgetRepository
import com.lomo.app.testing.fakes.FakeExternalAppCommandStore
import com.lomo.app.testing.fakes.FakeGitSyncRepository
import com.lomo.app.testing.fakes.FakeMediaRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.FakeS3SyncRepository
import com.lomo.app.testing.fakes.FakeSyncInboxRepository
import com.lomo.app.testing.fakes.FakeSyncPolicyRepository
import com.lomo.app.testing.fakes.FakeWebDavSyncRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.usecase.FakeDispatcherProvider
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.S3UnifiedSyncProvider
import com.lomo.domain.usecase.SetMemoPinnedUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.WebDavUnifiedSyncProvider
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MainViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Main screen state management, filtering, and gallery presentation.
 *
 * Scenarios:
 * - Given a workspace root is present, when the ViewModel initializes, then UI state transitions to Ready and paged memos are emitted.
 * - Given a workspace root is missing, when the ViewModel initializes, then UI state stays in a non-ready state.
 * - Given a cold-start asynchronously restores the root, when the ViewModel observes the restored root, then it starts paging without treating it as a root switch.
 * - Given the user searches for memos or filters by date, when the filter changes, then pagedUiMemos and galleryUiMemos emit filtered content.
 * - Given navigation requests open or focus memos, when the ViewModel queues app actions, then the
 *   memo actions are emitted in command order.
 * - Given a delete operation is triggered, when the repository completes, then visual stability collapse markers are removed.
 * - Given a memo has a reminder, when the user marks it done, then the repository is updated via the reminder coordinator.
 *
 * Observable outcomes:
 * - uiState reflects root availability.
 * - pagedUiMemos and galleryUiMemos emit filtered/remapped content.
 * - collectionUiState, deletingMemoIds, and errorMessage show collection-action state without absorbing Main-specific failures.
 * - appActionEvents correctly sequence memo navigation requests (Open/Focus).
 *
 * TDD proof:
 * - Fails before the fix when image-directory changes are not debounced, when concurrent gallery image-cache sync requests are not coalesced, when gallery initial loading is exposed as a true empty state, when observed root changes still route through the ordinary sync refresh pipeline, when image-map changes do not remap paged main-list rows, when cold-start Paging waits for the restored root before starting, when an asynchronously restored cold-start root is treated as a root switch, rebuilds the workspace, or recreates the DB paging source, when Main collection mutations are still locally owned instead of delegated to common collection state, or when marking a reminder as done is not propagated through ViewModel to ReminderCoordinator.
 *
 * Excludes:
 * - Compose rendering, navigation wiring, and repository implementation internals.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention with LomoList system and delegated collection mutations to common state.
 * - Old behavior/assertion being replaced: main collection mutations were locally owned instead of delegated through common collection state; image-directory changes were not debounced.
 * - Why old assertion is no longer correct: the MainScreen now uses LomoList animation components, paging source, and common collection state holders.
 * - Coverage preserved by: all ViewModel scenarios retained for memo loading, filter, paging, and image-map behaviors.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state transitions and paging behaviors, not internal animation or widget mechanics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<MainViewModel>()

    private lateinit var repository: FakeMemoStore
    private lateinit var sidebarStateHolder: MainSidebarStateHolder
    private lateinit var appConfigRepository: FakeAppConfigRepository
    private lateinit var gitSyncRepo: FakeGitSyncRepository
    private lateinit var mediaRepository: FakeMediaRepository
    private lateinit var webDavSyncRepository: FakeWebDavSyncRepository
    private lateinit var s3SyncRepository: FakeS3SyncRepository
    private lateinit var syncInboxRepository: FakeSyncInboxRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var appVersionRepository: FakeAppVersionRepository
    private lateinit var memoVersionRepository: FakeMemoVersionRepository
    private lateinit var appWidgetRepository: FakeAppWidgetRepository
    private lateinit var memoUiMapper: MemoUiMapper
    private lateinit var imageMapProvider: com.lomo.app.provider.FakeImageMapProvider
    private lateinit var workspaceStateResolver: FakeWorkspaceStateResolver
    private lateinit var audioPlayerManager: AudioPlayerManager
    private lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase
    private lateinit var dispatcherProvider: com.lomo.domain.usecase.DispatcherProvider
    private lateinit var reminderCoordinator: com.lomo.app.testing.fakes.FakeReminderCoordinator

    init {
        extension(MainDispatcherExtension(testDispatcher))
        beforeTest {
            repository = FakeMemoStore()
            sidebarStateHolder = MainSidebarStateHolder()
            appConfigRepository = FakeAppConfigRepository()
            gitSyncRepo = FakeGitSyncRepository()
            mediaRepository = FakeMediaRepository()
            webDavSyncRepository = FakeWebDavSyncRepository()
            s3SyncRepository = FakeS3SyncRepository()
            syncInboxRepository = FakeSyncInboxRepository()
            syncPolicyRepository = FakeSyncPolicyRepository()
            appVersionRepository = FakeAppVersionRepository()
            memoVersionRepository = FakeMemoVersionRepository()
            appWidgetRepository = FakeAppWidgetRepository()
            memoUiMapper = MemoUiMapper()
            imageMapProvider = com.lomo.app.provider.FakeImageMapProvider(mediaRepository)
            audioPlayerManager = com.lomo.app.testing.fakes.FakeAudioPlayerManager()
            workspaceStateResolver = FakeWorkspaceStateResolver()
            switchRootStorageUseCase = SwitchRootStorageUseCase(
                directorySettingsRepository = appConfigRepository,
                workspaceStateResolver = workspaceStateResolver
            )
            dispatcherProvider = FakeDispatcherProvider(testDispatcher)
            reminderCoordinator = com.lomo.app.testing.fakes.FakeReminderCoordinator()

            appVersionRepository.lastAppVersion = ""
        }

        afterTest {
            createdViewModels
                .asReversed()
                .forEach(::clearViewModel)
            createdViewModels.clear()
        }

        test("initial search query is empty") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                (viewModel.searchQuery.value) shouldBe ("")
            }
        }

        test("onSearch updates searchQuery") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.onSearch("test query")
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.searchQuery.value) shouldBe ("test query")
            }
        }

        test("clearFilters resets search query") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.onSearch("query")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.clearFilters()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.searchQuery.value) shouldBe ("")
            }
        }

        test("galleryUiMemos keeps only image memos and remains sorted by timestamp descending") {
            runTest(testDispatcher) {
                val newerImageMemo =
                    Memo(
                        id = "memo-image-new",
                        timestamp = 200L,
                        content = "![new](images/new.jpg)",
                        rawContent = "- 10:00 ![new](images/new.jpg)",
                        dateKey = "2026_03_08",
                        imageUrls = listOf("images/new.jpg"),
                    )
                val noImageMemo =
                    Memo(
                        id = "memo-no-image",
                        timestamp = 150L,
                        content = "plain text",
                        rawContent = "- 10:00 plain text",
                        dateKey = "2026_03_08",
                    )
                val olderImageMemo =
                    Memo(
                        id = "memo-image-old",
                        timestamp = 100L,
                        content = "![old](images/old.jpg)",
                        rawContent = "- 10:00 ![old](images/old.jpg)",
                        dateKey = "2026_03_07",
                        imageUrls = listOf("images/old.jpg"),
                    )
                repository.setActiveMemos(listOf(newerImageMemo, noImageMemo, olderImageMemo))

                val viewModel = createViewModel()
                val galleryUiMemos = viewModel.galleryUiMemos.first { it.size == 2 }

                (galleryUiMemos.map { it.memo.id }) shouldBe (listOf("memo-image-new", "memo-image-old"))
                (galleryUiMemos.map { it.imageUrls }) shouldBe (listOf(
                        persistentListOf("images/new.jpg"),
                        persistentListOf("images/old.jpg"),
                    ))
            }
        }

        test("galleryUiMemosState starts as loading before first gallery source emission") {
            runTest(testDispatcher) {
                // FakeMemoStore.getGalleryMemosList uses activeMemos flow.
                // To simulate a never-emitting flow, we could use a custom flow if needed,
                // but usually setActiveMemos(emptyList()) is enough if we just want to test initial state.
                repository.setActiveMemos(emptyList())

                val viewModel = createViewModel()

                (viewModel.galleryUiMemosState.value) shouldBe (GalleryUiMemosState.Loading)
            }
        }

        test("galleryUiMemosState reports true empty only after gallery source emits") {
            runTest(testDispatcher) {
                repository.setActiveMemos(emptyList())

                val viewModel = createViewModel()
                val loadedState =
                    viewModel.galleryUiMemosState.first { state ->
                        state is GalleryUiMemosState.Loaded
                    }

                (loadedState) shouldBe (GalleryUiMemosState.Loaded(emptyList()))
            }
        }

        test("date filter updates paged main list source instead of collecting full memo flow") {
                runTest(testDispatcher) {
                    val memoDate = LocalDate.of(2026, 3, 1)
                // FakeMemoStore already handles this
                // repository.getAllMemosList() is already driven by activeMemos in FakeMemoStore

                    val viewModel = createViewModel()
                    val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                    val collectJob =
                        backgroundScope.launch {
                            viewModel.pagedUiMemos.take(2).toList(pagingEmissions)
                        }

                    runCurrent()
                    viewModel.filterMemosByDate(memoDate)
                    advanceUntilIdle()

                    repository.verifyMainListPagingSourceCalled(
                        query = "",
                        filter = MemoListFilter(startDate = memoDate, endDate = memoDate),
                    )
                    repository.verifyGetAllMemosListNotCalled()
                    (pagingEmissions.size) shouldBe (2)
                    collectJob.cancel()
                }

            /*
             * Test Change Justification:
             * - Reason category: product contract changed after user-visible VFS regression.
             * - Old behavior/assertion being replaced: imageMap additions that did not affect the old in-memory
             *   dependency window were expected to avoid a new PagingData emission.
             * - Why old assertion is no longer correct: the paged main list maps each memo lazily, so the
             *   state holder cannot reliably know which loaded or soon-to-load rows reference a new SAF URI.
             *   Skipping the emission leaves main-list images stale while gallery images update.
             * - Coverage preserved by: the test still proves main-list paging stays independent from the full
             *   memo flow; it now also protects the visible image remapping path.
             * - Why this is not fitting the test to the implementation: the new assertion matches the reported
             *   product behavior: image location cache changes must be visible in the main list.
             */
        }

        test("pagedUiMemos emits new paging data when image map changes") {
            runTest(testDispatcher) {
                val memoDate = LocalDate.of(2026, 3, 8)
                val memo =
                    Memo(
                        id = "memo-paged-image",
                        timestamp =
                            memoDate
                                .atTime(10, 0)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli(),
                        content = "![img](foo.png)",
                        rawContent = "![img](foo.png)",
                        dateKey = "2026_03_08",
                        localDate = memoDate,
                    )
                val initialUri = mockk<android.net.Uri>(relaxed = true)
                val unrelatedUri = mockk<android.net.Uri>(relaxed = true)
                val imageMapFlow =
                    MutableStateFlow(
                        mapOf(
                            "foo.png" to initialUri,
                        ),
                    )
                imageMapProvider = FakeImageMapProvider(mediaRepository, imageMapFlow)
                mediaRepository.setImageLocations(emptyMap())
                repository.setMainListPagingSource(listOf(memo))
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/root"))

                val viewModel = createViewModel()
                val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.take(2).toList(pagingEmissions)
                    }

                advanceUntilIdle()
                (pagingEmissions.size) shouldBe (1)

                imageMapFlow.value =
                    imageMapFlow.value +
                        ("bar.png" to unrelatedUri)
                runCurrent()
                advanceUntilIdle()

                (pagingEmissions.size) shouldBe (2)
                collectJob.cancel()
            }
        }

        test("pagedUiMemos does not emit new paging data when allMemos list changes without ui dependency change") {
                runTest(testDispatcher) {
                    val memoDate = LocalDate.of(2026, 3, 8)
                    val pagedMemo =
                        Memo(
                            id = "memo-paged-stable",
                            timestamp =
                                memoDate
                                    .atTime(10, 0)
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli(),
                            content = "plain text",
                            rawContent = "plain text",
                            dateKey = "2026_03_08",
                            localDate = memoDate,
                        )
                repository.setActiveMemos(listOf(pagedMemo))
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/root"))

                    val viewModel = createViewModel()
                    val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                    val collectJob =
                        backgroundScope.launch {
                            viewModel.pagedUiMemos.take(2).toList(pagingEmissions)
                        }

                    advanceUntilIdle()
                    (pagingEmissions.size) shouldBe (1)

                    repository.setActiveMemos(listOf(
                            pagedMemo,
                            pagedMemo.copy(
                                id = "memo-extra",
                                timestamp = pagedMemo.timestamp - 1,
                                content = "no image markup either",
                                rawContent = "no image markup either",
                            ),
                        ))
                    advanceUntilIdle()

                    (pagingEmissions.size) shouldBe (1)
                    collectJob.cancel()
                }
        }

        test("delete keeps collapse marker until paged animation settles after repository removal") {
            runTest(testDispatcher) {
                val memo = memo("memo-visible-delete", LocalDate.of(2026, 3, 8), 10)
                repository.deleteResult = memo.id

                val viewModel = createViewModel()

                viewModel.deleteMemo(memo, null)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                viewModel.onPagedDeleteAnimationSettled(memo.id)
                runCurrent()
                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
            }
        }

        test("main collection mutations publish through common collection action state") {
            runTest(testDispatcher) {
                val memo = memo("memo-main-collection", LocalDate.of(2026, 3, 8), 11)
                repository.deleteResult = memo.id
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.collectionUiState.collect() }

                viewModel.deleteMemo(memo, null)
                runCurrent()

                viewModel.collectionUiState.value.deletingMemoIds shouldBe setOf(memo.id)
                viewModel.deletingMemoIds.value shouldBe viewModel.collectionUiState.value.deletingMemoIds

                runCurrent()

                viewModel.collectionUiState.value.deletingMemoIds shouldBe setOf(memo.id)

                viewModel.onPagedDeleteAnimationSettled(memo.id)
                runCurrent()

                viewModel.collectionUiState.value.deletingMemoIds shouldBe emptySet()
                repository.updateMemoFailure = IllegalStateException("database lock")

                viewModel.updateMemo(memo.copy(content = "- [ ] first item", rawContent = "- [ ] first item"), 0, true)
                advanceUntilIdle()

                viewModel.collectionUiState.value.errorMessage shouldBe "Failed to update todo: database lock"
                viewModel.errorMessage.value shouldBe viewModel.collectionUiState.value.errorMessage

                viewModel.clearError()
                runCurrent()

                viewModel.collectionUiState.value.errorMessage shouldBe null
                viewModel.errorMessage.value shouldBe null
                collectJob.cancel()
            }
        }

        test("main specific errors remain outside common collection action state") {
            runTest(testDispatcher) {
                val memo = memo("memo-pin-main-owned", LocalDate.of(2026, 3, 9), 8)
                repository.setMemoPinnedFailure = IllegalStateException("pin failed")
                val viewModel = createViewModel()
                val collectJob = backgroundScope.launch(testDispatcher) { viewModel.collectionUiState.collect() }

                viewModel.setMemoPinned(memo, true)
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to update pin status: pin failed"
                viewModel.collectionUiState.value.errorMessage shouldBe null

                viewModel.clearError()
                runCurrent()

                viewModel.errorMessage.value shouldBe null
                viewModel.collectionUiState.value.errorMessage shouldBe null
                collectJob.cancel()
            }
        }

        test("resolveMemoById falls back to repository single lookup without full refresh") {
            runTest(testDispatcher) {
                val memoId = "memo-single"
                val memo =
                    Memo(
                        id = memoId,
                        timestamp = 321L,
                        content = "memo-content",
                        rawContent = "- 10:00 memo-content",
                        dateKey = "2026_03_08",
                    )
                repository.setActiveMemos(listOf(memo))

                val viewModel = createViewModel()
                val resolved = viewModel.resolveMemoById(memoId)

                (resolved) shouldBe (memo)
                repository.verifyRefreshMemosNotCalled()
            }
        }

        test("appLockEnabled is shared as nullable state flow for splash and compose") {
            runTest(testDispatcher) {
                appConfigRepository.setAppLockEnabledNow(true)

                val viewModel = createViewModel()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.appLockEnabled.collect { }
                    }

                (viewModel.appLockEnabled.value) shouldBe null

                testDispatcher.scheduler.advanceUntilIdle()

                ((viewModel.appLockEnabled.value == true)) shouldBe true

                appConfigRepository.setAppLockEnabledNow(false)
                testDispatcher.scheduler.advanceUntilIdle()
                collectJob.cancel()

                ((viewModel.appLockEnabled.value == false)) shouldBe true
            }
        }

        test("updateMemoStartDate clears endDate when end is earlier") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.updateMemoEndDate(LocalDate.of(2026, 3, 1))
                viewModel.updateMemoStartDate(LocalDate.of(2026, 3, 5))
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.memoListFilter.value.startDate) shouldBe (LocalDate.of(2026, 3, 5))
                (viewModel.memoListFilter.value.endDate) shouldBe null
            }
        }

        test("sort option toggles ascending and descending on repeated taps") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
                (viewModel.memoListFilter.value.sortOption) shouldBe (MemoSortOption.UPDATED_TIME)
                ((viewModel.memoListFilter.value.sortAscending)) shouldBe true

                viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
                (viewModel.memoListFilter.value.sortOption) shouldBe (MemoSortOption.UPDATED_TIME)
                ((viewModel.memoListFilter.value.sortAscending)) shouldBe false

                viewModel.updateMemoSortOption(MemoSortOption.CREATED_TIME)
                (viewModel.memoListFilter.value.sortOption) shouldBe (MemoSortOption.CREATED_TIME)
                ((viewModel.memoListFilter.value.sortAscending)) shouldBe true
            }
        }

        test("start date only updates the paged main list source") {
            runTest(testDispatcher) {
                val startDate = LocalDate.of(2026, 3, 1)
                // FakeMemoStore handles this
                val viewModel = createViewModel()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.take(2).toList(mutableListOf())
                    }

                runCurrent()
                viewModel.updateMemoStartDate(startDate)
                advanceUntilIdle()

                repository.verifyMainListPagingSourceCalled(filter = MemoListFilter(startDate = startDate))
                collectJob.cancel()
            }
        }

        test("end date only updates the paged main list source") {
            runTest(testDispatcher) {
                val endDate = LocalDate.of(2026, 3, 1)
                // FakeMemoStore handles this
                val viewModel = createViewModel()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.take(2).toList(mutableListOf())
                    }

                runCurrent()
                viewModel.updateMemoEndDate(endDate)
                advanceUntilIdle()

                repository.verifyMainListPagingSourceCalled(filter = MemoListFilter(endDate = endDate))
                collectJob.cancel()
            }
        }

        test("clearMemoListFilter resets sort and date range") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
                viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
                viewModel.updateMemoStartDate(LocalDate.of(2026, 3, 1))
                viewModel.updateMemoEndDate(LocalDate.of(2026, 3, 10))
                viewModel.clearMemoListFilter()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.memoListFilter.value.sortOption) shouldBe (MemoSortOption.CREATED_TIME)
                ((viewModel.memoListFilter.value.sortAscending)) shouldBe false
                (viewModel.memoListFilter.value.startDate) shouldBe null
                (viewModel.memoListFilter.value.endDate) shouldBe null
            }
        }

        test("handleSharedText enqueues text event and consume removes it") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.handleSharedText("shared text")
                val queued = viewModel.sharedContentEvents.value
                (queued.size) shouldBe (1)
                ((queued.first().payload as MainViewModel.SharedContent.Text).content) shouldBe ("shared text")

                viewModel.consumeSharedContentEvent(queued.first().id)
                ((viewModel.sharedContentEvents.value.isEmpty())) shouldBe true
            }
        }

        test("handleSharedImage keeps pending queue until explicitly consumed") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                val firstUri = mockk<android.net.Uri>(relaxed = true)
                val secondUri = mockk<android.net.Uri>(relaxed = true)

                viewModel.handleSharedImage(firstUri)
                viewModel.handleSharedImage(secondUri)

                val pending = viewModel.pendingSharedImageEvents.value
                (pending.size) shouldBe (2)
                (pending[0].payload) shouldBe (firstUri)
                (pending[1].payload) shouldBe (secondUri)

                viewModel.consumePendingSharedImageEvent(pending[0].id)

                val remaining = viewModel.pendingSharedImageEvents.value
                (remaining.size) shouldBe (1)
                (remaining.single().payload) shouldBe (secondUri)
            }
        }

        test("uiState is no-directory when root is missing") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.NoDirectory)
            }
        }

        test("uiState is ready when root exists") {
            runTest(testDispatcher) {
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/root"))
                val viewModel = createViewModel()
                testDispatcher.scheduler.runCurrent()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Ready)
            }
        }

        test("requestOpenMemo and requestFocusMemo ignore blank ids") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.requestOpenMemo(" ")
                viewModel.requestFocusMemo("")

                ((viewModel.appActionEvents.value.isEmpty())) shouldBe true
            }
        }

        test("requestOpenMemo and requestFocusMemo enqueue ordered app actions") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()

                viewModel.requestOpenMemo("memo-open")
                viewModel.requestFocusMemo("memo-focus")

                val events = viewModel.appActionEvents.value
                (events.size) shouldBe (2)
                (events[0].payload) shouldBe (MainViewModel.AppAction.OpenMemo("memo-open"))
                (events[1].payload) shouldBe (MainViewModel.AppAction.FocusMemo("memo-focus"))
            }
        }

        test("requestFocusMemoInDefaultMainList clears main filters before enqueueing focus") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                val date = LocalDate.of(2026, 3, 9)

                viewModel.onSearch("query")
                viewModel.filterMemosByDate(date)
                viewModel.requestFocusMemoInDefaultMainList("memo-focus")

                (viewModel.searchQuery.value) shouldBe ("")
                (viewModel.memoListFilter.value) shouldBe (MemoListFilter())
                (viewModel.appActionEvents.value.single().payload) shouldBe (MainViewModel.AppAction.FocusMemo("memo-focus"))
            }
        }

        test("filterMemosByDate and clearMemoDateRange update date filter state") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                val date = LocalDate.of(2026, 3, 9)

                viewModel.filterMemosByDate(date)
                (viewModel.memoListFilter.value.startDate) shouldBe (date)
                (viewModel.memoListFilter.value.endDate) shouldBe (date)

                viewModel.clearMemoDateRange()
                (viewModel.memoListFilter.value.startDate) shouldBe null
                (viewModel.memoListFilter.value.endDate) shouldBe null
            }
        }

        test("refresh keeps generic error clear when refresh reports sync conflict") {
            runTest(testDispatcher) {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                val conflict =
                    com.lomo.domain.model.SyncConflictSet(
                        source = SyncBackendType.GIT,
                        files =
                            listOf(
                                com.lomo.domain.model.SyncConflictFile(
                                    relativePath = "2026_03_26.md",
                                    localContent = "local",
                                    remoteContent = "remote",
                                    isBinary = false,
                                ),
                            ),
                        timestamp = 123L,
                    )
                repository.refreshMemosFailure = com.lomo.domain.usecase.SyncConflictException(conflict)
                val viewModel = createViewModel()

                viewModel.refresh()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe null
            }
        }

        test("refresh exposes generic error when refresh fails") {
            runTest(testDispatcher) {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                repository.refreshMemosFailure = IllegalStateException("refresh failed")
                val viewModel = createViewModel()

                viewModel.refresh()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to refresh memos: refresh failed")
            }
        }

        test("automatic refresh runs when root directory is available") {
            runTest(testDispatcher) {
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/root"))
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)

                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                repository.resetRecordedCalls()

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.advanceUntilIdle()

                repository.verifyRefreshMemosCalled()
            }
        }

        test("automatic refresh stays idle when root directory is missing") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.runCurrent()

                repository.verifyRefreshMemosNotCalled()
            }
        }

        test("image directory changes are debounced before syncing image cache") {
            runTest(testDispatcher) {
                appConfigRepository.setLocation(StorageArea.IMAGE, null)
                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                mediaRepository.resetRecordedCalls()

                appConfigRepository.setLocation(StorageArea.IMAGE, StorageLocation("/images/one"))
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(100L)
                appConfigRepository.setLocation(StorageArea.IMAGE, StorageLocation("/images/two"))
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(100L)
                appConfigRepository.setLocation(StorageArea.IMAGE, StorageLocation("/images/three"))
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(299L)

                mediaRepository.verifyRefreshImageLocationsNotCalled()

                testDispatcher.scheduler.advanceTimeBy(1L)
                testDispatcher.scheduler.advanceUntilIdle()

                mediaRepository.verifyRefreshImageLocationsCalled()
                (viewModel.errorMessage.value) shouldBe null
            }
        }

        test("initial non null image directory does not trigger image cache sync") {
            runTest(testDispatcher) {
                val syncDebounceMillis = 300L
                appConfigRepository.setLocation(StorageArea.IMAGE, StorageLocation("/images/initial"))
                createViewModel()
                testDispatcher.scheduler.advanceTimeBy(syncDebounceMillis)
                testDispatcher.scheduler.advanceUntilIdle()

                mediaRepository.verifyRefreshImageLocationsNotCalled()
            }
        }

        test("syncImageCacheNow coalesces concurrent gallery refresh requests") {
                runTest(testDispatcher) {
                    val finishRefresh = CompletableDeferred<Unit>()
                    mediaRepository.setFinishRefresh(finishRefresh)
                    val viewModel = createViewModel()

                    viewModel.syncImageCacheNow()
                    runCurrent()

                    viewModel.syncImageCacheNow()
                    runCurrent()

                    mediaRepository.verifyRefreshImageLocationsCalled(exactly = 1)

                    finishRefresh.complete(Unit)
                    advanceUntilIdle()

                    viewModel.syncImageCacheNow()
                    advanceUntilIdle()

                    mediaRepository.verifyRefreshImageLocationsCalled(exactly = 2)
                }
        }

        test("root directory changes do not run ordinary refresh pipeline from observer") {
            runTest(testDispatcher) {
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/root/one"))

                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                repository.resetRecordedCalls()
                mediaRepository.resetRecordedCalls()

                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/root/two"))
                testDispatcher.scheduler.advanceUntilIdle()

                workspaceStateResolver.rebuildCount shouldBe 1
                repository.verifyRefreshMemosNotCalled()
                mediaRepository.verifyRefreshImageLocationsNotCalled()
                (viewModel.errorMessage.value) shouldBe null
            }
        }

        test("restored root on cold start does not rebuild workspace as a root change") {
                runTest(testDispatcher) {
                    val rootDirectoryFlow = MutableStateFlow<StorageLocation?>(StorageLocation("/root/current"))
                    appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/root/current"))
                appVersionRepository.lastAppVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

                    val viewModel = createViewModel()
                    testDispatcher.scheduler.advanceUntilIdle()

                    (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Ready)
                    workspaceStateResolver.rebuildCount shouldBe 0
                }

            /*
             * Test Change Justification:
             * - Reason category: product startup-performance contract correction.
             * - Old behavior/assertion being replaced: cold-start Paging was expected to wait until the
             *   restored root directory resolved.
             * - Why old assertion is no longer correct: the main memo rows come from Room and do not require
             *   the root path; root/image dependencies only affect UI media resolution and should not block
             *   or recreate the DB paging source.
             * - Coverage preserved by: this test still proves unresolved root keeps MainScreenState.Loading
             *   and still proves the restored root is not treated as a rebuild-worthy root switch.
             * - Why this is not fitting the test to the implementation: this locks the user-facing startup
             *   contract that pagination starts doing useful work immediately after process creation.
             */
        }

        test("cold start starts main list paging before restored root resolves") {
            runTest(testDispatcher) {
                val restoredRoot = StorageLocation("/root/current")
                val rootLookup = CompletableDeferred<StorageLocation?>()
                appConfigRepository.setLocation(StorageArea.ROOT, restoredRoot)
                appConfigRepository.setLocationDeferred(StorageArea.ROOT, rootLookup)
                appVersionRepository.lastAppVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

                val viewModel = createViewModel()
                val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.collect { pagingData ->
                            pagingEmissions += pagingData
                        }
                    }

                runCurrent()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Loading)
                (pagingEmissions.size) shouldBe (1)
                repository.verifyMainListPagingSourceCalled(
                    query = "",
                    filter = MemoListFilter(),
                )

                rootLookup.complete(restoredRoot)
                advanceUntilIdle()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Ready)
                workspaceStateResolver.rebuildCount shouldBe 0
                collectJob.cancel()
            }
        }

        test("automatic refresh is rate limited for repeated visible events") {
            runTest(testDispatcher) {
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/root"))
                syncPolicyRepository.updateRemoteSyncBackend(SyncBackendType.NONE)

                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                repository.resetRecordedCalls()

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.advanceUntilIdle()
                repository.verifyRefreshMemosCalled(exactly = 1)

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.advanceUntilIdle()
                repository.verifyRefreshMemosCalled(exactly = 1)
            }
        }

        test("setMemoPinned exposes mapped error when pin update fails") {
            runTest(testDispatcher) {
                val memo = memo("memo-pin", LocalDate.of(2026, 3, 9), 8)
                repository.setMemoPinnedFailure = IllegalStateException("pin failed")
                val viewModel = createViewModel()

                viewModel.setMemoPinned(memo, true)
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to update pin status: pin failed")
            }
        }

        test("createDefaultDirectories exposes mapped error when workspace init fails") {
            runTest(testDispatcher) {
                mediaRepository.ensureCategoryWorkspaceFailure = IllegalStateException("mkdir failed")
                val viewModel = createViewModel()

                viewModel.createDefaultDirectories(true, false)
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to create directories: mkdir failed")
            }
        }

        test("given a memo reminder when user marks reminder done then repository is updated via reminder coordinator") {
            runTest(testDispatcher) {
                val viewModel = createViewModel()
                viewModel.markReminderDone("memo-id-1", "@2026-05-22-17:51x5")
                advanceUntilIdle()

                reminderCoordinator.lastMarkedDoneMemoId shouldBe "memo-id-1"
                reminderCoordinator.lastMarkedDoneTokenRaw shouldBe "@2026-05-22-17:51x5"
                reminderCoordinator.markDoneCalledCount shouldBe 1
            }
        }
    }

    private fun createViewModel(): MainViewModel {
        val appConfigStateProvider = createAppConfigStateProvider()
        return MainViewModel(
            mainMemoListQueryUseCase = mainMemoListQueryUseCase(),
            observeActiveDayCountUseCase = observeActiveDayCountUseCase(),
            setMemoPinnedUseCase = setMemoPinnedUseCase(),
            appConfigStateProvider = appConfigStateProvider,
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            sidebarStateHolder = sidebarStateHolder,
            versionHistoryCoordinator =
                MainVersionHistoryCoordinator(
                    loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(memoVersionRepository),
                    restoreMemoRevisionUseCase =
                        RestoreMemoRevisionUseCase(
                            com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository),
                        ),
                ),
            memoUiMapper = memoUiMapper,
            imageMapProvider = imageMapProvider,
            mainMemoMutationCoordinator =
                MainMemoMutationCoordinator(
                    deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository)),
                    toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository), ValidateMemoContentUseCase()),
                    appWidgetRepository = appWidgetRepository,
                ),
            workspaceCoordinator =
                MainWorkspaceCoordinator(
                    initializeWorkspaceUseCase = InitializeWorkspaceUseCase(appConfigRepository, mediaRepository),
                    refreshMemosUseCase =
                        RefreshMemosUseCase(
                            SyncAndRebuildUseCase(
                                memoRepository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository),
                                syncProviderRegistry = syncProviderRegistry(),
                                syncPolicyRepository = syncPolicyRepository,
                            ),
                        ),
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    mediaRepository = mediaRepository,
                ),
            startupCoordinator =
                MainStartupCoordinator(
                    startupMaintenanceUseCase =
                        StartupMaintenanceUseCase(
                            mediaRepository = mediaRepository,
                            initializeWorkspaceUseCase = InitializeWorkspaceUseCase(appConfigRepository, mediaRepository),
                            syncAndRebuildUseCase =
                                SyncAndRebuildUseCase(
                                    memoRepository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository),
                                    syncProviderRegistry = syncProviderRegistry(),
                                    syncPolicyRepository = syncPolicyRepository,
                                ),
                            syncProviderRegistry = syncProviderRegistry(),
                            appVersionRepository = appVersionRepository,
                            syncInboxRepository = syncInboxRepository,
                        ),
                    appConfigStateProvider =
                        appConfigStateProvider,
                    audioPlayerManager = audioPlayerManager,
            ),
            markReminderDoneUseCase = MarkReminderDoneUseCase(reminderCoordinator),
            dispatcherProvider = dispatcherProvider,
            externalAppCommandStore = FakeExternalAppCommandStore(),
        ).also(createdViewModels::add)
    }

    private fun mainMemoListQueryUseCase(): MainMemoListQueryUseCase {
        val fakeQueryRepository = com.lomo.app.testing.fakes.FakeMemoQueryRepository(repository)
        return MainMemoListQueryUseCase(
            mainListQueryRepository = fakeQueryRepository,
            memoListQueryRepository = fakeQueryRepository,
        )
    }

    private fun observeActiveDayCountUseCase(): ObserveActiveDayCountUseCase =
        ObserveActiveDayCountUseCase(
            com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(repository),
        )

    private fun setMemoPinnedUseCase(): SetMemoPinnedUseCase =
        SetMemoPinnedUseCase(
            com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository),
        )

    private fun createAppConfigStateProvider(): com.lomo.app.feature.common.AppConfigStateProvider =
        com.lomo.app.feature.common.AppConfigStateProvider(
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            appPreferencesSnapshotRepository = appConfigRepository,
            customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
            appScope = CoroutineScope(SupervisorJob() + testDispatcher),
        )

    private fun syncProviderRegistry(): SyncProviderRegistry =
        SyncProviderRegistry(
            providers =
                setOf(
                    GitUnifiedSyncProvider(gitSyncRepo),
                    WebDavUnifiedSyncProvider(webDavSyncRepository),
                    S3UnifiedSyncProvider(s3SyncRepository),
                    InboxUnifiedSyncProvider(syncInboxRepository, appConfigRepository),
                ),
        )

    private fun clearViewModel(viewModel: MainViewModel) {
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

    private fun fixedPagingSource(memos: List<Memo>): PagingSource<Int, Memo> =
        object : PagingSource<Int, Memo>() {
            override val jumpingSupported: Boolean = true

            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
                LoadResult.Page(
                    data = memos,
                    prevKey = null,
                    nextKey = null,
                    itemsBefore = 0,
                    itemsAfter = 0,
                )

            override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = null
        }

    private class FakeWorkspaceStateResolver : com.lomo.domain.repository.WorkspaceStateResolver {
        var rebuildCount = 0
        override suspend fun rebuildFromCurrentWorkspace() {
            rebuildCount++
        }
    }
}
