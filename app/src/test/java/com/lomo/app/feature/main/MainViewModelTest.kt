package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.app.BuildConfig
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.media.AudioPlayerManager
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.S3UnifiedSyncProvider
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
import io.mockk.spyk
import io.mockk.verify
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * Test Contract:
 * - Unit under test: MainViewModel
 * - Behavior focus: reachable main-screen search and date-filter state, gallery loading/selection,
 *   image-cache refresh trigger behavior, user-visible coordinator outcomes, and automatic foreground
 *   refresh when a workspace root is available.
 * - Observable outcomes: exposed StateFlow values, derived memo lists, and delegated use-case interactions.
 * - Red phase: Fails before the fix when image-directory changes are not debounced, when concurrent
 *   gallery image-cache sync requests are not coalesced, when gallery initial loading is exposed as
 *   a true empty state, when observed root changes still route through the ordinary sync refresh
 *   pipeline, when image-map changes do not remap paged main-list rows, when cold-start Paging waits
 *   for the restored root before starting, or when an asynchronously restored cold-start root is
 *   treated as a root switch, rebuilds the workspace, or recreates the DB paging source.
 * - Excludes: Compose rendering, navigation wiring, and repository implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<MainViewModel>()

    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var sidebarStateHolder: MainSidebarStateHolder
    private lateinit var appConfigRepository: com.lomo.domain.repository.AppConfigRepository
    private lateinit var gitSyncRepo: com.lomo.domain.repository.GitSyncRepository
    private lateinit var mediaRepository: com.lomo.domain.repository.MediaRepository
    private lateinit var webDavSyncRepository: com.lomo.domain.repository.WebDavSyncRepository
    private lateinit var s3SyncRepository: com.lomo.domain.repository.S3SyncRepository
    private lateinit var syncInboxRepository: com.lomo.domain.repository.SyncInboxRepository
    private lateinit var syncPolicyRepository: com.lomo.domain.repository.SyncPolicyRepository
    private lateinit var appVersionRepository: com.lomo.domain.repository.AppVersionRepository
    private lateinit var memoVersionRepository: com.lomo.domain.repository.MemoVersionRepository
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var memoUiMapper: MemoUiMapper
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var audioPlayerManager: AudioPlayerManager
    private lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase

    init {
        extension(MainDispatcherExtension(testDispatcher))
        beforeTest {
            repository = mockk(relaxed = true)
            sidebarStateHolder = MainSidebarStateHolder()
            appConfigRepository = mockk(relaxed = true)
            gitSyncRepo = mockk(relaxed = true)
            mediaRepository = mockk(relaxed = true)
            webDavSyncRepository = mockk(relaxed = true)
            s3SyncRepository = mockk(relaxed = true)
            syncInboxRepository = mockk(relaxed = true)
            syncPolicyRepository = mockk(relaxed = true)
            appVersionRepository = mockk(relaxed = true)
            memoVersionRepository = mockk(relaxed = true)
            appWidgetRepository = mockk(relaxed = true)
            memoUiMapper = spyk(MemoUiMapper())
            imageMapProvider = emptyImageMapProvider()
            audioPlayerManager = mockk(relaxed = true)
            switchRootStorageUseCase = mockk(relaxed = true)

            every { repository.isSyncing() } returns flowOf(false)
            every { repository.getAllMemosList() } returns flowOf(emptyList<Memo>())
            every { repository.getGalleryMemosList() } returns flowOf(emptyList<Memo>())
            every { repository.getMemosByDateRange(any(), any()) } returns flowOf(emptyList<Memo>())
            every { repository.searchMemosList(any()) } returns flowOf(emptyList<Memo>())
            every { repository.getMainListPagingSource(any(), any()) } returns fixedPagingSource(emptyList())
            every { repository.getMainListCountFlow(any(), any()) } returns flowOf(0)
            every { repository.getMemosByTagList(any()) } returns flowOf(emptyList<Memo>())
            every { repository.getMemoCountFlow() } returns flowOf(0)
            every { repository.getMemoTimestampsFlow() } returns flowOf(emptyList())
            every { repository.getMemoCountByDateFlow() } returns flowOf(emptyMap())
            every { repository.getTagCountsFlow() } returns flowOf(emptyList<com.lomo.domain.model.MemoTagCount>())
            every { repository.getActiveDayCount() } returns flowOf(0)
            every { gitSyncRepo.isGitSyncEnabled() } returns flowOf(false)
            every { gitSyncRepo.getSyncOnRefreshEnabled() } returns flowOf(false)
            every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(false)
            every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(false)
            every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(false)
            every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(false)
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
            coEvery {
                syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
            } returns UnifiedSyncResult.Success(
                provider = SyncBackendType.INBOX,
                message = "processed",
            )
            every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(null)
            coEvery { appConfigRepository.currentRootLocation() } returns null
            every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns flowOf(null)
            coEvery { appConfigRepository.currentLocation(StorageArea.IMAGE) } returns null
            every { appConfigRepository.observeLocation(StorageArea.VOICE) } returns flowOf(null)

            every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
            every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
            every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
            every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
            every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
            every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(false)
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.getMemoActionOrder() } returns flowOf(emptyList())
            every { appConfigRepository.getMemoActionOrdersByScope() } returns flowOf(emptyMap())
            every { appConfigRepository.getInputToolbarToolOrder() } returns flowOf(emptyList())
            every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
            every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
            every { appConfigRepository.isAppLockEnabled() } returns flowOf(false)
            every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)

            coEvery { appVersionRepository.getLastAppVersionOnce() } returns ""
            coEvery { appVersionRepository.updateLastAppVersion(any()) } returns Unit
            coEvery { memoVersionRepository.listMemoRevisions(any(), any(), any()) } returns
                MemoRevisionPage(
                    items = emptyList<MemoRevision>(),
                    nextCursor = null,
                )
            coEvery { memoVersionRepository.restoreMemoRevision(any(), any()) } returns Unit
        }

        afterTest {
            createdViewModels
                .asReversed()
                .forEach(::clearViewModel)
            createdViewModels.clear()
}

        test("initial search query is empty") {
            runTest {
                val viewModel = createViewModel()
                (viewModel.searchQuery.value) shouldBe ("")
            }
        }

        test("onSearch updates searchQuery") {
            runTest {
                val viewModel = createViewModel()

                viewModel.onSearch("test query")
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.searchQuery.value) shouldBe ("test query")
            }
        }

        test("clearFilters resets search query") {
            runTest {
                val viewModel = createViewModel()

                viewModel.onSearch("query")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.clearFilters()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.searchQuery.value) shouldBe ("")
            }
        }

        test("galleryUiMemos keeps only image memos and remains sorted by timestamp descending") {
            runTest {
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
                every { repository.getGalleryMemosList() } returns flowOf(listOf(newerImageMemo, noImageMemo, olderImageMemo))
                every { repository.getAllMemosList() } returns flow { error("full memo flow should stay unused for gallery") }

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
            runTest {
                every { repository.getGalleryMemosList() } returns
                    flow {
                        awaitCancellation()
                    }

                val viewModel = createViewModel()

                (viewModel.galleryUiMemosState.value) shouldBe (GalleryUiMemosState.Loading)
            }
        }

        test("galleryUiMemosState reports true empty only after gallery source emits") {
            runTest {
                every { repository.getGalleryMemosList() } returns flowOf(emptyList())

                val viewModel = createViewModel()
                val loadedState =
                    viewModel.galleryUiMemosState.first { state ->
                        state is GalleryUiMemosState.Loaded
                    }

                (loadedState) shouldBe (GalleryUiMemosState.Loaded(emptyList()))
            }
        }

        test("date filter updates paged main list source instead of collecting full memo flow") {
                runTest {
                    val memoDate = LocalDate.of(2026, 3, 1)
                    every {
                        repository.getMainListPagingSource(
                            query = "",
                            filter = MemoListFilter(startDate = memoDate, endDate = memoDate),
                        )
                    } returns fixedPagingSource(listOf(memo("memo-date-range", memoDate, 10)))
                    every { repository.getAllMemosList() } returns flow { error("full memo flow should stay unused for date filtering") }

                    val viewModel = createViewModel()
                    val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                    val collectJob =
                        backgroundScope.launch {
                            viewModel.pagedUiMemos.take(2).toList(pagingEmissions)
                        }

                    runCurrent()
                    viewModel.filterMemosByDate(memoDate)
                    advanceUntilIdle()

                    verify(atLeast = 1) {
                        repository.getMainListPagingSource(
                            query = "",
                            filter = MemoListFilter(startDate = memoDate, endDate = memoDate),
                        )
                    }
                    verify(exactly = 0) { repository.getAllMemosList() }
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
            runTest {
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
                imageMapProvider = mockk(relaxed = true)
                every { imageMapProvider.imageMap } returns imageMapFlow
                every {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(),
                    )
                } returns fixedPagingSource(listOf(memo))

                val viewModel = createViewModel()
                val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.take(2).toList(pagingEmissions)
                    }

                runCurrent()
                (pagingEmissions.size) shouldBe (1)

                imageMapFlow.value =
                    imageMapFlow.value +
                        ("bar.png" to unrelatedUri)
                advanceUntilIdle()

                (pagingEmissions.size) shouldBe (2)
                collectJob.cancel()
            }
        }

        test("pagedUiMemos does not emit new paging data when allMemos list changes without ui dependency change") {
                runTest {
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
                    val allMemosFlow = MutableStateFlow(listOf(pagedMemo))
                    every { repository.getAllMemosList() } returns allMemosFlow
                    every {
                        repository.getMainListPagingSource(
                            query = "",
                            filter = MemoListFilter(),
                        )
                    } returns fixedPagingSource(listOf(pagedMemo))

                    val viewModel = createViewModel()
                    val pagingEmissions = mutableListOf<androidx.paging.PagingData<MemoUiModel>>()
                    val collectJob =
                        backgroundScope.launch {
                            viewModel.pagedUiMemos.take(2).toList(pagingEmissions)
                        }

                    runCurrent()
                    (pagingEmissions.size) shouldBe (1)

                    allMemosFlow.value =
                        listOf(
                            pagedMemo,
                            pagedMemo.copy(
                                id = "memo-extra",
                                timestamp = pagedMemo.timestamp - 1,
                                content = "no image markup either",
                                rawContent = "no image markup either",
                            ),
                        )
                    advanceUntilIdle()

                    (pagingEmissions.size) shouldBe (1)
                    collectJob.cancel()
                }

            /*
             * Test Change Justification:
             * - Reason category: product contract changed.
             * - Old behavior/assertion being replaced: this test previously asserted that visibleUiMemos becomes empty
             *   as soon as the fade completes.
             * - Why the previous assertion is no longer correct: removing the row from the rendered list at fade
             *   completion causes a second structural list update when the repository result arrives, which shows up as
             *   a visible delete-time rebound.
             * - Coverage preserved by: the updated scenario still locks delete timing, delete markers, and final removal
             *   after repository completion, while now protecting the single-collapse contract.
             * - Why this is not fitting the test to the implementation: the revised assertion encodes the user-visible
             *   motion requirement rather than a private state-flow detail.
             */
        }

        test("delete keeps collapse marker until paged animation settles after repository removal") {
            runTest {
                val memo = memo("memo-visible-delete", LocalDate.of(2026, 3, 8), 10)
                val finishDelete = CompletableDeferred<Unit>()
                coEvery { repository.deleteMemo(memo) } coAnswers {
                    finishDelete.await()
                }

                val viewModel = createViewModel()

                viewModel.deleteMemo(memo)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                finishDelete.complete(Unit)
                runCurrent()

                ((viewModel.deletingMemoIds.value.contains(memo.id))) shouldBe true

                viewModel.onPagedDeleteAnimationSettled(memo.id)
                ((viewModel.deletingMemoIds.value.isEmpty())) shouldBe true
            }
        }

        test("resolveMemoById falls back to repository single lookup without full refresh") {
            runTest {
                val memoId = "memo-single"
                val memo =
                    Memo(
                        id = memoId,
                        timestamp = 321L,
                        content = "memo-content",
                        rawContent = "- 10:00 memo-content",
                        dateKey = "2026_03_08",
                    )
                coEvery { repository.getMemoById(memoId) } returns memo

                val viewModel = createViewModel()
                val resolved = viewModel.resolveMemoById(memoId)

                (resolved) shouldBe (memo)
                coVerify(exactly = 0) { repository.refreshMemos() }
            }
        }

        test("appLockEnabled is shared as nullable state flow for splash and compose") {
            runTest {
                val appLockEnabledFlow = MutableStateFlow(true)
                every { appConfigRepository.isAppLockEnabled() } returns appLockEnabledFlow

                val viewModel = createViewModel()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.appLockEnabled.collect { }
                    }

                (viewModel.appLockEnabled.value) shouldBe null

                testDispatcher.scheduler.advanceUntilIdle()

                ((viewModel.appLockEnabled.value == true)) shouldBe true

                appLockEnabledFlow.value = false
                testDispatcher.scheduler.advanceUntilIdle()
                collectJob.cancel()

                ((viewModel.appLockEnabled.value == true)) shouldBe false
                ((viewModel.appLockEnabled.value == false)) shouldBe true
            }
        }

        test("updateMemoStartDate clears endDate when end is earlier") {
            runTest {
                val viewModel = createViewModel()

                viewModel.updateMemoEndDate(LocalDate.of(2026, 3, 1))
                viewModel.updateMemoStartDate(LocalDate.of(2026, 3, 5))
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.memoListFilter.value.startDate) shouldBe (LocalDate.of(2026, 3, 5))
                (viewModel.memoListFilter.value.endDate) shouldBe null
            }
        }

        test("sort option toggles ascending and descending on repeated taps") {
            runTest {
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
            runTest {
                val startDate = LocalDate.of(2026, 3, 1)
                every {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(startDate = startDate),
                    )
                } returns fixedPagingSource(listOf(memo("start", startDate, 8)))
                val viewModel = createViewModel()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.take(2).toList(mutableListOf())
                    }

                runCurrent()
                viewModel.updateMemoStartDate(startDate)
                advanceUntilIdle()

                verify(atLeast = 1) {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(startDate = startDate),
                    )
                }
                collectJob.cancel()
            }
        }

        test("end date only updates the paged main list source") {
            runTest {
                val endDate = LocalDate.of(2026, 3, 1)
                every {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(endDate = endDate),
                    )
                } returns fixedPagingSource(listOf(memo("end", endDate, 8)))
                val viewModel = createViewModel()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.pagedUiMemos.take(2).toList(mutableListOf())
                    }

                runCurrent()
                viewModel.updateMemoEndDate(endDate)
                advanceUntilIdle()

                verify(atLeast = 1) {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(endDate = endDate),
                    )
                }
                collectJob.cancel()
            }
        }

        test("clearMemoListFilter resets sort and date range") {
            runTest {
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
            runTest {
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
            runTest {
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
            runTest {
                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.NoDirectory)
            }
        }

        test("uiState is ready when root exists") {
            runTest {
                every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(StorageLocation("/tmp/root"))
                coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/tmp/root")
                val viewModel = createViewModel()
                testDispatcher.scheduler.runCurrent()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Ready)
            }
        }

        test("requestOpenMemo and requestFocusMemo ignore blank ids") {
            runTest {
                val viewModel = createViewModel()

                viewModel.requestOpenMemo(" ")
                viewModel.requestFocusMemo("")

                ((viewModel.appActionEvents.value.isEmpty())) shouldBe true
            }
        }

        test("requestCreateMemo open and focus enqueue ordered app actions") {
            runTest {
                val viewModel = createViewModel()

                viewModel.requestCreateMemo()
                viewModel.requestOpenMemo("memo-open")
                viewModel.requestFocusMemo("memo-focus")

                val events = viewModel.appActionEvents.value
                (events.size) shouldBe (3)
                (events[0].payload) shouldBe (MainViewModel.AppAction.CreateMemo)
                (events[1].payload) shouldBe (MainViewModel.AppAction.OpenMemo("memo-open"))
                (events[2].payload) shouldBe (MainViewModel.AppAction.FocusMemo("memo-focus"))
            }
        }

        test("requestFocusMemoInDefaultMainList clears main filters before enqueueing focus") {
            runTest {
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
            runTest {
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
            runTest {
                every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
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
                coEvery { repository.refreshMemos() } throws com.lomo.domain.usecase.SyncConflictException(conflict)
                val viewModel = createViewModel()

                viewModel.refresh()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe null
            }
        }

        test("refresh exposes generic error when refresh fails") {
            runTest {
                every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
                coEvery { repository.refreshMemos() } throws IllegalStateException("refresh failed")
                val viewModel = createViewModel()

                viewModel.refresh()
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to refresh memos: refresh failed")
            }
        }

        test("automatic refresh runs when root directory is available") {
            runTest {
                every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(StorageLocation("/tmp/root"))
                coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/tmp/root")
                every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)

                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                clearMocks(repository, answers = false, recordedCalls = true)

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { repository.refreshMemos() }
            }
        }

        test("automatic refresh stays idle when root directory is missing") {
            runTest {
                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.runCurrent()

                coVerify(exactly = 0) { repository.refreshMemos() }
            }
        }

        test("image directory changes are debounced before syncing image cache") {
            runTest {
                val imageDirectoryFlow = MutableStateFlow<StorageLocation?>(null)
                every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns imageDirectoryFlow
                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                clearMocks(mediaRepository, answers = false, recordedCalls = true)

                imageDirectoryFlow.value = StorageLocation("/images/one")
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(100L)
                imageDirectoryFlow.value = StorageLocation("/images/two")
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(100L)
                imageDirectoryFlow.value = StorageLocation("/images/three")
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(299L)

                coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }

                testDispatcher.scheduler.advanceTimeBy(1L)
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
                (viewModel.errorMessage.value) shouldBe null
            }
        }

        test("initial non null image directory does not trigger image cache sync") {
            runTest {
                val syncDebounceMillis = 300L
                val imageDirectoryFlow = MutableStateFlow<StorageLocation?>(StorageLocation("/images/initial"))
                every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns imageDirectoryFlow
                coEvery { appConfigRepository.currentLocation(StorageArea.IMAGE) } returns StorageLocation("/images/initial")
                createViewModel()
                testDispatcher.scheduler.advanceTimeBy(syncDebounceMillis)
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }
            }
        }

        test("deferred startup reenables image cache sync for restored initial image directory") {
            runTest {
                val syncDebounceMillis = 300L
                val imageDirectoryFlow = MutableStateFlow<StorageLocation?>(StorageLocation("/images/initial"))
                every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns imageDirectoryFlow
                coEvery { appConfigRepository.currentLocation(StorageArea.IMAGE) } returns StorageLocation("/images/initial")
                val startupCoordinator = mockk<MainStartupCoordinator>()
                val workspaceCoordinator = mockk<MainWorkspaceCoordinator>(relaxed = true)
                coEvery { startupCoordinator.initializeRootDirectory() } returns null
                every { startupCoordinator.observeRootDirectoryChanges() } returns flowOf<String?>(null)
                every { startupCoordinator.observeVoiceDirectoryChanges() } returns flowOf<String?>(null)
                coEvery { startupCoordinator.runDeferredStartupTasks(any()) } returns Unit

                MainViewModel(
                    memoUiCoordinator = MemoUiCoordinator(repository),
                    appConfigStateProvider = createAppConfigStateProvider(),
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    sidebarStateHolder = sidebarStateHolder,
                    versionHistoryCoordinator =
                        MainVersionHistoryCoordinator(
                            loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(memoVersionRepository),
                            restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(memoVersionRepository),
                        ),
                    memoUiMapper = memoUiMapper,
                    imageMapProvider = imageMapProvider,
                    mainMemoMutationCoordinator =
                        MainMemoMutationCoordinator(
                            deleteMemoUseCase = DeleteMemoUseCase(repository),
                            toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(repository, ValidateMemoContentUseCase()),
                            appWidgetRepository = appWidgetRepository,
                        ),
                    workspaceCoordinator = workspaceCoordinator,
                    startupCoordinator = startupCoordinator,
                ).also(createdViewModels::add)

                testDispatcher.scheduler.advanceTimeBy(syncDebounceMillis)
                testDispatcher.scheduler.advanceUntilIdle()
                coVerify(exactly = 0) { workspaceCoordinator.syncImageCacheBestEffort() }

                createdViewModels.last().runDeferredStartupTasksIfNeeded()
                testDispatcher.scheduler.runCurrent()
                testDispatcher.scheduler.advanceTimeBy(syncDebounceMillis)
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { workspaceCoordinator.syncImageCacheBestEffort() }
            }
        }

        test("syncImageCacheNow coalesces concurrent gallery refresh requests") {
                runTest {
                    val firstRefreshStarted = CompletableDeferred<Unit>()
                    val finishRefresh = CompletableDeferred<Unit>()
                    coEvery { mediaRepository.refreshImageLocations() } coAnswers {
                        firstRefreshStarted.complete(Unit)
                        finishRefresh.await()
                    }
                    val viewModel = createViewModel()

                    viewModel.syncImageCacheNow()
                    runCurrent()
                    firstRefreshStarted.await()

                    viewModel.syncImageCacheNow()
                    runCurrent()

                    coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }

                    finishRefresh.complete(Unit)
                    advanceUntilIdle()

                    viewModel.syncImageCacheNow()
                    advanceUntilIdle()

                    coVerify(exactly = 2) { mediaRepository.refreshImageLocations() }
                }

            /*
             * Test Change Justification:
             * - Reason category: product contract corrected after root-switch rebuild was moved into the
             *   switch-root use case.
             * - Old behavior/assertion being replaced: observed root-directory changes were expected to run
             *   the ordinary refresh use case and then image-cache refresh from MainViewModel.
             * - Why old assertion is no longer correct: root switching now owns the workspace rebuild path;
             *   running the ordinary refresh pipeline from the observer can invoke sync refresh behavior after
             *   a settings directory switch and leave the newly selected local workspace partially rebuilt.
             * - Coverage preserved by: SwitchRootStorageUseCase covers local workspace rebuild ordering, while
             *   this test protects the must-not-happen duplicate standard refresh path and verifies the observer
             *   delegates to the local rebuild entrypoint.
             * - Why this is not fitting the test to the implementation: the corrected assertion encodes the
             *   user-visible bug that switching directories must not collapse the selected local memo set into
             *   a partial sync-derived result.
             */
        }

        test("root directory changes do not run ordinary refresh pipeline from observer") {
            runTest {
                val rootDirectoryFlow = MutableStateFlow<StorageLocation?>(StorageLocation("/root/one"))
                every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns rootDirectoryFlow
                every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
                coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/root/one")

                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                clearMocks(repository, mediaRepository, answers = false, recordedCalls = true)

                rootDirectoryFlow.value = StorageLocation("/root/two")
                testDispatcher.scheduler.advanceUntilIdle()

                coVerify(exactly = 1) { switchRootStorageUseCase.rebuildCurrentWorkspace() }
                coVerify(exactly = 0) { repository.refreshMemos() }
                coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }
                (viewModel.errorMessage.value) shouldBe null
            }
        }

        test("restored root on cold start does not rebuild workspace as a root change") {
                runTest {
                    val rootDirectoryFlow = MutableStateFlow<StorageLocation?>(StorageLocation("/root/current"))
                    every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns rootDirectoryFlow
                    coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/root/current")
                    coEvery { appVersionRepository.getLastAppVersionOnce() } returns
                        "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

                    val viewModel = createViewModel()
                    testDispatcher.scheduler.advanceUntilIdle()

                    (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Ready)
                    coVerify(exactly = 0) { switchRootStorageUseCase.rebuildCurrentWorkspace() }
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
            runTest {
                val restoredRoot = StorageLocation("/root/current")
                val rootLookup = CompletableDeferred<StorageLocation?>()
                every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(restoredRoot)
                coEvery { appConfigRepository.currentRootLocation() } coAnswers { rootLookup.await() }
                coEvery { appVersionRepository.getLastAppVersionOnce() } returns
                    "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

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
                verify(exactly = 1) {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(),
                    )
                }

                rootLookup.complete(restoredRoot)
                advanceUntilIdle()

                (viewModel.uiState.value) shouldBe (MainViewModel.MainScreenState.Ready)
                verify(exactly = 1) {
                    repository.getMainListPagingSource(
                        query = "",
                        filter = MemoListFilter(),
                    )
                }
                coVerify(exactly = 0) { switchRootStorageUseCase.rebuildCurrentWorkspace() }
                collectJob.cancel()
            }
        }

        test("automatic refresh is rate limited for repeated visible events") {
            runTest {
                every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(StorageLocation("/tmp/root"))
                coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/tmp/root")
                every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)

                val viewModel = createViewModel()
                testDispatcher.scheduler.advanceUntilIdle()
                clearMocks(repository, answers = false, recordedCalls = true)

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.advanceUntilIdle()
                coVerify(exactly = 1) { repository.refreshMemos() }

                viewModel.requestAutomaticRefreshForVisibleScreen()
                testDispatcher.scheduler.advanceUntilIdle()
                coVerify(exactly = 1) { repository.refreshMemos() }
            }
        }

        test("setMemoPinned exposes mapped error when pin update fails") {
            runTest {
                val memo = memo("memo-pin", LocalDate.of(2026, 3, 9), 8)
                coEvery { repository.setMemoPinned(memo.id, true) } throws IllegalStateException("pin failed")
                val viewModel = createViewModel()

                viewModel.setMemoPinned(memo, true)
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to update pin status: pin failed")
            }
        }

        test("createDefaultDirectories exposes mapped error when workspace init fails") {
            runTest {
                coEvery { mediaRepository.ensureCategoryWorkspace(com.lomo.domain.model.MediaCategory.IMAGE) } throws
                    IllegalStateException("mkdir failed")
                val viewModel = createViewModel()

                viewModel.createDefaultDirectories(true, false)
                testDispatcher.scheduler.advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to create directories: mkdir failed")
            }
        }

        test("startup sync inbox conflict is emitted as sync conflict event") {
            runTest {
                every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(StorageLocation("/tmp/root"))
                coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/tmp/root")
                val conflicts =
                    SyncConflictSet(
                        source = SyncBackendType.INBOX,
                        files =
                            listOf(
                                SyncConflictFile(
                                    relativePath = "inbox/2026_04_13.md",
                                    localContent = "local",
                                    remoteContent = "remote",
                                    isBinary = false,
                                ),
                            ),
                        timestamp = 123L,
                    )
                coEvery {
                    syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                } returns UnifiedSyncResult.Conflict(
                    provider = SyncBackendType.INBOX,
                    message = "conflict",
                    conflicts = conflicts,
                )

                val viewModel = createViewModel()
                val event = async { viewModel.syncConflictEvent.first() }

                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.runDeferredStartupTasksIfNeeded()
                testDispatcher.scheduler.advanceUntilIdle()

                (event.await()) shouldBe (conflicts)
                (viewModel.errorMessage.value) shouldBe null
            }
        }
    }

    private fun createViewModel(): MainViewModel {
        val appConfigStateProvider = createAppConfigStateProvider()
        return MainViewModel(
            memoUiCoordinator = MemoUiCoordinator(repository),
            appConfigStateProvider = appConfigStateProvider,
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            sidebarStateHolder = sidebarStateHolder,
            versionHistoryCoordinator =
                MainVersionHistoryCoordinator(
                    loadMemoRevisionHistoryUseCase = LoadMemoRevisionHistoryUseCase(memoVersionRepository),
                    restoreMemoRevisionUseCase = RestoreMemoRevisionUseCase(memoVersionRepository),
                ),
            memoUiMapper = memoUiMapper,
            imageMapProvider = imageMapProvider,
            mainMemoMutationCoordinator =
                MainMemoMutationCoordinator(
                    deleteMemoUseCase = DeleteMemoUseCase(repository),
                    toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(repository, ValidateMemoContentUseCase()),
                    appWidgetRepository = appWidgetRepository,
                ),
            workspaceCoordinator =
                MainWorkspaceCoordinator(
                    initializeWorkspaceUseCase = InitializeWorkspaceUseCase(appConfigRepository, mediaRepository),
                    refreshMemosUseCase =
                        RefreshMemosUseCase(
                            SyncAndRebuildUseCase(
                                memoRepository = repository,
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
                                    memoRepository = repository,
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
        ).also(createdViewModels::add)
    }

    private fun createAppConfigStateProvider(): com.lomo.app.feature.common.AppConfigStateProvider =
        com.lomo.app.feature.common.AppConfigStateProvider(
            AppConfigUiCoordinator(appConfigRepository),
            CoroutineScope(SupervisorJob() + testDispatcher),
        )

    private fun syncProviderRegistry(): SyncProviderRegistry =
        SyncProviderRegistry(
            providers =
                listOf(
                    GitUnifiedSyncProvider(gitSyncRepo),
                    WebDavUnifiedSyncProvider(webDavSyncRepository),
                    S3UnifiedSyncProvider(s3SyncRepository),
                    InboxUnifiedSyncProvider(syncInboxRepository, mockk(relaxed = true)),
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

}
