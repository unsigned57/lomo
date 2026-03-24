package com.lomo.app.feature.main

import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoVersionHistoryUseCase
import com.lomo.domain.usecase.ApplyMainMemoFilterUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.RestoreMemoVersionUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var sidebarStateHolder: MainSidebarStateHolder
    private lateinit var appConfigRepository: com.lomo.domain.repository.AppConfigRepository
    private lateinit var gitSyncRepo: com.lomo.domain.repository.GitSyncRepository
    private lateinit var mediaRepository: com.lomo.domain.repository.MediaRepository
    private lateinit var webDavSyncRepository: com.lomo.domain.repository.WebDavSyncRepository
    private lateinit var syncPolicyRepository: com.lomo.domain.repository.SyncPolicyRepository
    private lateinit var appVersionRepository: com.lomo.domain.repository.AppVersionRepository
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
        syncPolicyRepository = mockk(relaxed = true)
        appVersionRepository = mockk(relaxed = true)
        appWidgetRepository = mockk(relaxed = true)
        memoUiMapper = MemoUiMapper()
        imageMapProvider = mockk(relaxed = true)
        audioPlayerManager = mockk(relaxed = true)
        switchRootStorageUseCase = mockk(relaxed = true)

        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())
        every { repository.isSyncing() } returns flowOf(false)
        every { repository.getAllMemosList() } returns flowOf(emptyList<Memo>())
        every { repository.searchMemosList(any()) } returns flowOf(emptyList<Memo>())
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
        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
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
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { appConfigRepository.isAppLockEnabled() } returns flowOf(false)
        every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)

        coEvery { appVersionRepository.getLastAppVersionOnce() } returns ""
        coEvery { appVersionRepository.updateLastAppVersion(any()) } returns Unit
    }

    @After
    fun tearDown() {
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
    fun `initial selected tag is null`() =
        runTest {
            val viewModel = createViewModel()
            assertNull(viewModel.selectedTag.value)
        }

    @Test
    fun `onTagSelected sets tag`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onTagSelected("work")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("work", viewModel.selectedTag.value)
        }

    @Test
    fun `onTagSelected toggles same tag to null`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onTagSelected("work")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onTagSelected("work")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.selectedTag.value)
        }

    @Test
    fun `clearFilters resets search and tag`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSearch("query")
            viewModel.onTagSelected("tag")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearFilters()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("", viewModel.searchQuery.value)
            assertNull(viewModel.selectedTag.value)
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
            every { repository.getAllMemosList() } returns flowOf(listOf(newerImageMemo, noImageMemo, olderImageMemo))

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
    fun `delete animation state does not rebuild uiMemos list`() =
        runTest {
            val memo = memo("memo-delete", LocalDate.of(2026, 3, 8), 10)
            every { repository.getAllMemosList() } returns flowOf(listOf(memo))

            val viewModel = createViewModel()
            val initialUiMemos = viewModel.uiMemos.first { it.size == 1 }

            viewModel.deleteMemo(memo)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.deletingMemoIds.value.contains(memo.id))
            assertSame(initialUiMemos, viewModel.uiMemos.value)
            assertSame(initialUiMemos.first(), viewModel.uiMemos.value.first())
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
    fun `start date only keeps memos on and after selected day`() =
        runTest {
            every { repository.getAllMemosList() } returns
                flowOf(
                    listOf(
                        memo("before", LocalDate.of(2026, 2, 28), 8),
                        memo("start", LocalDate.of(2026, 3, 1), 8),
                        memo("after", LocalDate.of(2026, 3, 2), 8),
                    ),
                )
            val viewModel = createViewModel()

            viewModel.updateMemoStartDate(LocalDate.of(2026, 3, 1))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("after", "start"), viewModel.memos.value.map { it.id })
        }

    @Test
    fun `end date only keeps memos on and before selected day`() =
        runTest {
            every { repository.getAllMemosList() } returns
                flowOf(
                    listOf(
                        memo("before", LocalDate.of(2026, 2, 28), 8),
                        memo("end", LocalDate.of(2026, 3, 1), 8),
                        memo("after", LocalDate.of(2026, 3, 2), 8),
                    ),
                )
            val viewModel = createViewModel()

            viewModel.updateMemoEndDate(LocalDate.of(2026, 3, 1))
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("end", "before"), viewModel.memos.value.map { it.id })
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
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(MainViewModel.MainScreenState.Ready, viewModel.uiState.value)
        }

    private fun createViewModel(): MainViewModel =
        MainViewModel(
            memoUiCoordinator = MemoUiCoordinator(repository),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            sidebarStateHolder = sidebarStateHolder,
            versionHistoryCoordinator =
                MainVersionHistoryCoordinator(
                    loadMemoVersionHistoryUseCase = LoadMemoVersionHistoryUseCase(gitSyncRepo),
                    restoreMemoVersionUseCase =
                        RestoreMemoVersionUseCase(
                            UpdateMemoContentUseCase(
                                repository = repository,
                                validator = ValidateMemoContentUseCase(),
                                resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
                                deleteMemoUseCase = DeleteMemoUseCase(repository),
                            ),
                        ),
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
                            SyncAndRebuildUseCase(repository, gitSyncRepo, webDavSyncRepository, syncPolicyRepository),
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
                                    repository,
                                    gitSyncRepo,
                                    webDavSyncRepository,
                                    syncPolicyRepository,
                                ),
                            appVersionRepository = appVersionRepository,
                        ),
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    audioPlayerManager = audioPlayerManager,
                ),
            applyMainMemoFilterUseCase = ApplyMainMemoFilterUseCase(),
            resolveMainMemoQueryUseCase = ResolveMainMemoQueryUseCase(),
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
