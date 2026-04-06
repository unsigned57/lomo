package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import com.lomo.app.BuildConfig
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.media.AudioPlayerManager
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.usecase.ApplyMainMemoFilterUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: MainViewModel
 * - Behavior focus: main-screen state transitions while a root-directory refresh is still running,
 *   including switching from a populated directory to a different one.
 * - Observable outcomes: exposed uiState values before and after refresh completion, plus the
 *   absence of premature Ready emissions while directory switching is pending.
 * - Red phase: Fails before the fix because switching to a new root while the old root already has
 *   memos keeps uiState at Ready until refreshMemos completes, so the loading state is never
 *   exposed and the UI can flash the empty state.
 * - Excludes: Compose rendering, navigation wiring, repository implementation internals, and
 *   actual filesystem scanning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelInitialImportStateTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: MemoRepository
    private lateinit var sidebarStateHolder: MainSidebarStateHolder
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var gitSyncRepo: GitSyncRepository
    private lateinit var mediaRepository: MediaRepository
    private lateinit var webDavSyncRepository: WebDavSyncRepository
    private lateinit var s3SyncRepository: S3SyncRepository
    private lateinit var syncPolicyRepository: SyncPolicyRepository
    private lateinit var appVersionRepository: AppVersionRepository
    private lateinit var memoVersionRepository: MemoVersionRepository
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var audioPlayerManager: AudioPlayerManager
    private lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase
    private lateinit var rootLocationFlow: MutableStateFlow<StorageLocation?>
    private lateinit var allMemosFlow: MutableStateFlow<List<Memo>>

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
        syncPolicyRepository = mockk(relaxed = true)
        appVersionRepository = mockk(relaxed = true)
        memoVersionRepository = mockk(relaxed = true)
        appWidgetRepository = mockk(relaxed = true)
        imageMapProvider = emptyImageMapProvider()
        audioPlayerManager = mockk(relaxed = true)
        switchRootStorageUseCase = mockk(relaxed = true)
        rootLocationFlow = MutableStateFlow(null)
        allMemosFlow = MutableStateFlow(emptyList())

        every { repository.isSyncing() } returns flowOf(false)
        every { repository.getAllMemosList() } returns allMemosFlow
        every { repository.searchMemosList(any()) } returns allMemosFlow
        every { repository.getMemosByTagList(any()) } returns flowOf(emptyList())
        every { repository.getMemoCountFlow() } returns flowOf(0)
        every { repository.getMemoTimestampsFlow() } returns flowOf(emptyList())
        every { repository.getMemoCountByDateFlow() } returns flowOf(emptyMap())
        every { repository.getTagCountsFlow() } returns flowOf(emptyList())
        every { repository.getActiveDayCount() } returns flowOf(0)
        every { gitSyncRepo.isGitSyncEnabled() } returns flowOf(false)
        every { gitSyncRepo.getSyncOnRefreshEnabled() } returns flowOf(false)
        every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(false)
        every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(false)
        every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(false)
        every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(false)
        every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
        every { appConfigRepository.observeLocation(StorageArea.ROOT) } returns rootLocationFlow
        coEvery { appConfigRepository.currentRootLocation() } coAnswers { rootLocationFlow.value }
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
        coEvery { appVersionRepository.getLastAppVersionOnce() } returns
            "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        coEvery { appVersionRepository.updateLastAppVersion(any()) } returns Unit
        coEvery { memoVersionRepository.listMemoRevisions(any(), any(), any()) } returns
            MemoRevisionPage(
                items = emptyList<MemoRevision>(),
                nextCursor = null,
            )
        coEvery { memoVersionRepository.restoreMemoRevision(any(), any()) } returns Unit
        coEvery { switchRootStorageUseCase.updateRootLocation(any()) } coAnswers {
            rootLocationFlow.value = firstArg()
        }
    }

    @After
    fun tearDown() {
        settleMainDispatcher()
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState is initial-importing while first refresh after root selection is running and no memos exist`() =
        runTest {
            val refreshStarted = CompletableDeferred<Unit>()
            val allowRefreshToFinish = CompletableDeferred<Unit>()
            val refreshFinished = CompletableDeferred<Unit>()
            coEvery { repository.refreshMemos() } coAnswers {
                refreshStarted.complete(Unit)
                allowRefreshToFinish.await()
                refreshFinished.complete(Unit)
            }
            val viewModel = createViewModel()
            try {
                advanceUntilIdle()
                rootLocationFlow.value = StorageLocation("/tmp/large-root")

                refreshStarted.await()

                assertEquals(MainViewModel.MainScreenState.InitialImporting, viewModel.uiState.value)

                allowRefreshToFinish.complete(Unit)
                refreshFinished.await()
                advanceUntilIdle()
            } finally {
                clearViewModel(viewModel)
            }
        }

    @Test
    fun `root change does not emit ready before initial-importing while first refresh is pending`() =
        runTest {
            val refreshStarted = CompletableDeferred<Unit>()
            val allowRefreshToFinish = CompletableDeferred<Unit>()
            val refreshFinished = CompletableDeferred<Unit>()
            val observedStates = mutableListOf<MainViewModel.MainScreenState>()
            coEvery { repository.refreshMemos() } coAnswers {
                refreshStarted.complete(Unit)
                allowRefreshToFinish.await()
                refreshFinished.complete(Unit)
            }
            val viewModel = createViewModel()
            try {
                advanceUntilIdle()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.uiState.drop(1).collect { state ->
                            observedStates += state
                        }
                    }

                try {
                    rootLocationFlow.value = StorageLocation("/tmp/large-root")
                    runCurrent()
                    refreshStarted.await()

                    assertFalse(observedStates.contains(MainViewModel.MainScreenState.Ready))
                    assertEquals(MainViewModel.MainScreenState.InitialImporting, viewModel.uiState.value)

                    allowRefreshToFinish.complete(Unit)
                    refreshFinished.await()
                    advanceUntilIdle()
                } finally {
                    collectJob.cancelAndJoin()
                }
            } finally {
                clearViewModel(viewModel)
            }
        }

    @Test
    fun `uiState returns to ready after first refresh completes with empty memo list`() =
        runTest {
            val refreshStarted = CompletableDeferred<Unit>()
            val allowRefreshToFinish = CompletableDeferred<Unit>()
            val refreshFinished = CompletableDeferred<Unit>()
            coEvery { repository.refreshMemos() } coAnswers {
                refreshStarted.complete(Unit)
                allowRefreshToFinish.await()
                refreshFinished.complete(Unit)
            }
            val viewModel = createViewModel()
            try {
                advanceUntilIdle()
                rootLocationFlow.value = StorageLocation("/tmp/large-root")
                refreshStarted.await()

                allowRefreshToFinish.complete(Unit)
                refreshFinished.await()
                awaitUiState(viewModel, MainViewModel.MainScreenState.Ready)
                assertFalse(viewModel.memos.value.isNotEmpty())
            } finally {
                clearViewModel(viewModel)
            }
        }

    @Test
    fun `uiState switches to initial-importing during root refresh when previous directory had memos`() =
        runTest {
            allMemosFlow.value = listOf(memo("memo-1", LocalDate.of(2026, 3, 31), 9))
            rootLocationFlow.value = StorageLocation("/tmp/old-root")
            coEvery { switchRootStorageUseCase.updateRootLocation(any()) } coAnswers {
                val location = firstArg<StorageLocation>()
                rootLocationFlow.value = location
                if (location.raw == "/tmp/new-root") {
                    awaitCancellation()
                }
            }
            val viewModel = createViewModel()
            try {
                advanceUntilIdle()
                viewModel.onDirectorySelected("/tmp/new-root")
                runCurrent()

                assertEquals(MainViewModel.MainScreenState.InitialImporting, viewModel.uiState.value)
            } finally {
                clearViewModel(viewModel)
            }
        }

    @Test
    fun `populated-directory switch does not emit ready before initial-importing while refresh is pending`() =
        runTest {
            val observedStates = mutableListOf<MainViewModel.MainScreenState>()
            allMemosFlow.value = listOf(memo("memo-1", LocalDate.of(2026, 3, 31), 9))
            rootLocationFlow.value = StorageLocation("/tmp/old-root")
            coEvery { switchRootStorageUseCase.updateRootLocation(any()) } coAnswers {
                val location = firstArg<StorageLocation>()
                rootLocationFlow.value = location
                if (location.raw == "/tmp/new-root") {
                    awaitCancellation()
                }
            }
            val viewModel = createViewModel()
            try {
                advanceUntilIdle()
                val collectJob =
                    backgroundScope.launch {
                        viewModel.uiState.drop(1).collect { state ->
                            observedStates += state
                        }
                    }

                try {
                    viewModel.onDirectorySelected("/tmp/new-root")
                    runCurrent()

                    assertFalse(observedStates.contains(MainViewModel.MainScreenState.Ready))
                    assertEquals(
                        MainViewModel.MainScreenState.InitialImporting,
                        observedStates.firstOrNull(),
                    )
                    assertEquals(MainViewModel.MainScreenState.InitialImporting, viewModel.uiState.value)
                } finally {
                    collectJob.cancelAndJoin()
                }
            } finally {
                clearViewModel(viewModel)
            }
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
            memoUiMapper = MemoUiMapper(),
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
                                repository,
                                gitSyncRepo,
                                webDavSyncRepository,
                                s3SyncRepository,
                                syncPolicyRepository,
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
                                    repository,
                                    gitSyncRepo,
                                    webDavSyncRepository,
                                    s3SyncRepository,
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

    private fun awaitUiState(
        viewModel: MainViewModel,
        expected: MainViewModel.MainScreenState,
        timeoutMillis: Long = 5_000,
    ) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
        while (viewModel.uiState.value != expected && System.nanoTime() < deadlineNanos) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        assertEquals(expected, viewModel.uiState.value)
    }

    private fun clearViewModel(viewModel: MainViewModel) {
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(viewModel)
        settleMainDispatcher()
    }

    private fun settleMainDispatcher() {
        repeat(5) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
    }
}
