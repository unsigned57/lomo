package com.lomo.app.feature.main

import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var sidebarStateHolder: MainSidebarStateHolder
    private lateinit var appConfigRepository: com.lomo.domain.repository.AppConfigRepository
    private lateinit var gitSyncRepo: com.lomo.domain.repository.GitSyncRepository
    private lateinit var mediaRepository: com.lomo.domain.repository.MediaRepository
    private lateinit var appVersionRepository: com.lomo.domain.repository.AppVersionRepository
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var memoUiMapper: MemoUiMapper
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var audioPlayerController: com.lomo.domain.repository.AudioPlaybackController
    private lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        repository = mockk(relaxed = true)
        sidebarStateHolder = MainSidebarStateHolder()
        appConfigRepository = mockk(relaxed = true)
        gitSyncRepo = mockk(relaxed = true)
        mediaRepository = mockk(relaxed = true)
        appVersionRepository = mockk(relaxed = true)
        appWidgetRepository = mockk(relaxed = true)
        memoUiMapper = MemoUiMapper()
        imageMapProvider = mockk(relaxed = true)
        audioPlayerController = mockk(relaxed = true)
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
        every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns flowOf(null)
        coEvery { appConfigRepository.currentRootLocation() } returns null
        every { appConfigRepository.observeLocation(StorageArea.IMAGE) } returns flowOf(null)
        every { appConfigRepository.observeLocation(StorageArea.VOICE) } returns flowOf(null)

        every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
        every { appConfigRepository.getShareCardStyle() } returns flowOf(ShareCardStyle.CLEAN)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
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
            repository = repository,
            appConfigRepository = appConfigRepository,
            sidebarStateHolder = sidebarStateHolder,
            versionHistoryCoordinator = MainVersionHistoryCoordinator(repository, gitSyncRepo),
            memoUiMapper = memoUiMapper,
            imageMapProvider = imageMapProvider,
            mainMemoMutationUseCase =
                MainMemoMutationUseCase(
                    deleteMemoUseCase = DeleteMemoUseCase(repository),
                    toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(repository, ValidateMemoContentUseCase()),
                    appWidgetRepository = appWidgetRepository,
                ),
            workspaceCoordinator =
                MainWorkspaceCoordinator(
                    repository = repository,
                    initializeWorkspaceUseCase = InitializeWorkspaceUseCase(appConfigRepository, mediaRepository),
                    refreshMemosUseCase = RefreshMemosUseCase(SyncAndRebuildUseCase(repository, gitSyncRepo)),
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    mediaRepository = mediaRepository,
                ),
            startupCoordinator =
                MainStartupCoordinator(
                    appConfigRepository = appConfigRepository,
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = InitializeWorkspaceUseCase(appConfigRepository, mediaRepository),
                    syncAndRebuildUseCase = SyncAndRebuildUseCase(repository, gitSyncRepo),
                    appVersionRepository = appVersionRepository,
                    audioPlayerController = audioPlayerController,
                ),
            resolveMainMemoQueryUseCase = ResolveMainMemoQueryUseCase(),
        )
}
