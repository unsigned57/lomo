package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MainViewModel
 * - Behavior focus: reachable main-screen search and date-filter state, gallery selection, image-cache refresh trigger behavior, user-visible coordinator
 *   outcomes, and automatic foreground refresh when a workspace root is available.
 * - Observable outcomes: exposed StateFlow values, derived memo lists, and delegated use-case interactions.
 * - Red phase: Fails before the fix when rapid image-directory changes trigger repeated cache refreshes instead of a single debounced refresh.
 * - Excludes: Compose rendering, navigation wiring, and repository implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

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
        every { appConfigRepository.observeLocation(StorageArea.VOICE) } returns flowOf(null)

        every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
        every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(false)
        every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
        every { appConfigRepository.getMemoActionOrder() } returns flowOf(emptyList())
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

    @After
    fun tearDown() {
        createdViewModels
            .asReversed()
            .forEach(::clearViewModel)
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial search query is empty`() =
        runTest {
            val viewModel = createViewModel()
            assertEquals("", viewModel.searchQuery.value)
        }

    @Test
    fun `onSearch updates searchQuery`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSearch("test query")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("test query", viewModel.searchQuery.value)
        }

    @Test
    fun `clearFilters resets search query`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSearch("query")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearFilters()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("", viewModel.searchQuery.value)
        }

    @Test
    fun `galleryUiMemos keeps only image memos and remains sorted by timestamp descending`() =
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

            assertEquals(
                listOf("memo-image-new", "memo-image-old"),
                galleryUiMemos.map { it.memo.id },
            )
            assertEquals(
                listOf(
                    persistentListOf("images/new.jpg"),
                    persistentListOf("images/old.jpg"),
                ),
                galleryUiMemos.map { it.imageUrls },
            )
        }

    @Test
    fun `date filter updates paged main list source instead of collecting full memo flow`() =
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
            assertEquals(2, pagingEmissions.size)
            collectJob.cancel()
        }

    @Test
    fun `pagedUiMemos does not emit new paging data when image map adds unrelated image`() =
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
            assertEquals(1, pagingEmissions.size)

            imageMapFlow.value =
                imageMapFlow.value +
                    ("bar.png" to unrelatedUri)
            advanceUntilIdle()

            assertEquals(1, pagingEmissions.size)
            collectJob.cancel()
        }

    @Test
    fun `pagedUiMemos does not emit new paging data when allMemos list changes without ui dependency change`() =
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
            assertEquals(1, pagingEmissions.size)

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

            assertEquals(1, pagingEmissions.size)
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
    @Test
    fun `delete keeps collapse marker until paged animation settles after repository removal`() =
        runTest {
            val memo = memo("memo-visible-delete", LocalDate.of(2026, 3, 8), 10)
            val finishDelete = CompletableDeferred<Unit>()
            coEvery { repository.deleteMemo(memo) } coAnswers {
                finishDelete.await()
            }

            val viewModel = createViewModel()

            viewModel.deleteMemo(memo)
            runCurrent()

            assertTrue(viewModel.deletingMemoIds.value.contains(memo.id))

            finishDelete.complete(Unit)
            runCurrent()

            assertTrue(viewModel.deletingMemoIds.value.contains(memo.id))

            viewModel.onPagedDeleteAnimationSettled(memo.id)
            assertTrue(viewModel.deletingMemoIds.value.isEmpty())
        }

    @Test
    fun `resolveMemoById falls back to repository single lookup without full refresh`() =
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

            assertEquals(memo, resolved)
            coVerify(exactly = 0) { repository.refreshMemos() }
        }

    @Test
    fun `appLockEnabled is shared as nullable state flow for splash and compose`() =
        runTest {
            val appLockEnabledFlow = MutableStateFlow(true)
            every { appConfigRepository.isAppLockEnabled() } returns appLockEnabledFlow

            val viewModel = createViewModel()

            assertNull(viewModel.appLockEnabled.value)

            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.appLockEnabled.value == true)

            appLockEnabledFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.appLockEnabled.value == true)
            assertTrue(viewModel.appLockEnabled.value == false)
        }

    @Test
    fun `updateMemoStartDate clears endDate when end is earlier`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.updateMemoEndDate(LocalDate.of(2026, 3, 1))
            viewModel.updateMemoStartDate(LocalDate.of(2026, 3, 5))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LocalDate.of(2026, 3, 5), viewModel.memoListFilter.value.startDate)
            assertNull(viewModel.memoListFilter.value.endDate)
        }

    @Test
    fun `sort option toggles ascending and descending on repeated taps`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
            assertEquals(MemoSortOption.UPDATED_TIME, viewModel.memoListFilter.value.sortOption)
            assertTrue(viewModel.memoListFilter.value.sortAscending)

            viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
            assertEquals(MemoSortOption.UPDATED_TIME, viewModel.memoListFilter.value.sortOption)
            assertFalse(viewModel.memoListFilter.value.sortAscending)

            viewModel.updateMemoSortOption(MemoSortOption.CREATED_TIME)
            assertEquals(MemoSortOption.CREATED_TIME, viewModel.memoListFilter.value.sortOption)
            assertTrue(viewModel.memoListFilter.value.sortAscending)
        }

    @Test
    fun `start date only updates the paged main list source`() =
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

    @Test
    fun `end date only updates the paged main list source`() =
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

    @Test
    fun `clearMemoListFilter resets sort and date range`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
            viewModel.updateMemoSortOption(MemoSortOption.UPDATED_TIME)
            viewModel.updateMemoStartDate(LocalDate.of(2026, 3, 1))
            viewModel.updateMemoEndDate(LocalDate.of(2026, 3, 10))
            viewModel.clearMemoListFilter()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(MemoSortOption.CREATED_TIME, viewModel.memoListFilter.value.sortOption)
            assertFalse(viewModel.memoListFilter.value.sortAscending)
            assertNull(viewModel.memoListFilter.value.startDate)
            assertNull(viewModel.memoListFilter.value.endDate)
        }

    @Test
    fun `handleSharedText enqueues text event and consume removes it`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.handleSharedText("shared text")
            val queued = viewModel.sharedContentEvents.value
            assertEquals(1, queued.size)
            assertEquals("shared text", (queued.first().payload as MainViewModel.SharedContent.Text).content)

            viewModel.consumeSharedContentEvent(queued.first().id)
            assertTrue(viewModel.sharedContentEvents.value.isEmpty())
        }

    @Test
    fun `handleSharedImage keeps pending queue until explicitly consumed`() =
        runTest {
            val viewModel = createViewModel()
            val firstUri = mockk<android.net.Uri>(relaxed = true)
            val secondUri = mockk<android.net.Uri>(relaxed = true)

            viewModel.handleSharedImage(firstUri)
            viewModel.handleSharedImage(secondUri)

            val pending = viewModel.pendingSharedImageEvents.value
            assertEquals(2, pending.size)
            assertEquals(firstUri, pending[0].payload)
            assertEquals(secondUri, pending[1].payload)

            viewModel.consumePendingSharedImageEvent(pending[0].id)

            val remaining = viewModel.pendingSharedImageEvents.value
            assertEquals(1, remaining.size)
            assertEquals(secondUri, remaining.single().payload)
        }

    @Test
    fun `uiState is no-directory when root is missing`() =
        runTest {
            val viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(MainViewModel.MainScreenState.NoDirectory, viewModel.uiState.value)
        }

    @Test
    fun `uiState is ready when root exists`() =
        runTest {
            every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(StorageLocation("/tmp/root"))
            coEvery { appConfigRepository.currentRootLocation() } returns StorageLocation("/tmp/root")
            val viewModel = createViewModel()
            testDispatcher.scheduler.runCurrent()

            assertEquals(MainViewModel.MainScreenState.Ready, viewModel.uiState.value)
        }

    @Test
    fun `requestOpenMemo and requestFocusMemo ignore blank ids`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.requestOpenMemo(" ")
            viewModel.requestFocusMemo("")

            assertTrue(viewModel.appActionEvents.value.isEmpty())
        }

    @Test
    fun `requestCreateMemo open and focus enqueue ordered app actions`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.requestCreateMemo()
            viewModel.requestOpenMemo("memo-open")
            viewModel.requestFocusMemo("memo-focus")

            val events = viewModel.appActionEvents.value
            assertEquals(3, events.size)
            assertEquals(MainViewModel.AppAction.CreateMemo, events[0].payload)
            assertEquals(MainViewModel.AppAction.OpenMemo("memo-open"), events[1].payload)
            assertEquals(MainViewModel.AppAction.FocusMemo("memo-focus"), events[2].payload)
        }

    @Test
    fun `filterMemosByDate and clearMemoDateRange update date filter state`() =
        runTest {
            val viewModel = createViewModel()
            val date = LocalDate.of(2026, 3, 9)

            viewModel.filterMemosByDate(date)
            assertEquals(date, viewModel.memoListFilter.value.startDate)
            assertEquals(date, viewModel.memoListFilter.value.endDate)

            viewModel.clearMemoDateRange()
            assertNull(viewModel.memoListFilter.value.startDate)
            assertNull(viewModel.memoListFilter.value.endDate)
        }

    @Test
    fun `refresh keeps generic error clear when refresh reports sync conflict`() =
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

            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `refresh exposes generic error when refresh fails`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
            coEvery { repository.refreshMemos() } throws IllegalStateException("refresh failed")
            val viewModel = createViewModel()

            viewModel.refresh()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Failed to refresh memos: refresh failed", viewModel.errorMessage.value)
        }

    @Test
    fun `automatic refresh runs when root directory is available`() =
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

    @Test
    fun `automatic refresh stays idle when root directory is missing`() =
        runTest {
            val viewModel = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.requestAutomaticRefreshForVisibleScreen()
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 0) { repository.refreshMemos() }
        }

    @Test
    fun `image directory changes are debounced before syncing image cache`() =
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
            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `automatic refresh is rate limited for repeated visible events`() =
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

    @Test
    fun `setMemoPinned exposes mapped error when pin update fails`() =
        runTest {
            val memo = memo("memo-pin", LocalDate.of(2026, 3, 9), 8)
            coEvery { repository.setMemoPinned(memo.id, true) } throws IllegalStateException("pin failed")
            val viewModel = createViewModel()

            viewModel.setMemoPinned(memo, true)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Failed to update pin status: pin failed", viewModel.errorMessage.value)
        }

    @Test
    fun `createDefaultDirectories exposes mapped error when workspace init fails`() =
        runTest {
            coEvery { mediaRepository.ensureCategoryWorkspace(com.lomo.domain.model.MediaCategory.IMAGE) } throws
                IllegalStateException("mkdir failed")
            val viewModel = createViewModel()

            viewModel.createDefaultDirectories(true, false)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Failed to create directories: mkdir failed", viewModel.errorMessage.value)
        }

    @Test
    fun `startup sync inbox conflict is emitted as sync conflict event`() =
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

            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceTimeBy(350L)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(conflicts, event.await())
            assertNull(viewModel.errorMessage.value)
        }

    private fun createViewModel(): MainViewModel =
        MainViewModel(
            memoUiCoordinator = MemoUiCoordinator(repository),
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
                    repository = repository,
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
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    audioPlayerManager = audioPlayerManager,
            ),
        ).also(createdViewModels::add)

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
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
                LoadResult.Page(
                    data = memos,
                    prevKey = null,
                    nextKey = null,
                )

            override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = null
        }

}
