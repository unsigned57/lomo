package com.lomo.app.feature.main

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


import androidx.lifecycle.ViewModel
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.media.AudioPlayerManager
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeMemoRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.S3UnifiedSyncProvider
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.WebDavUnifiedSyncProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Main screen loading and directory switching state orchestration.
 * - Scenarios:
 *   - Given starting workspace rebuild on new empty directory, UI must report InitialImporting until complete.
 *   - Given root directory change, confirm Ready state is not flashed prematurely before InitialImporting starts.
 *   - Given workspace rebuild finishes, UI state transitions cleanly to Ready.
 * - Observable outcomes:
 *   - uiState stateflow values over time during deferred import/rebuild operations.
 * - TDD proof: Confirms robust lifecycle status management during asynchronous I/O background refreshes.
 * - Excludes: Database writes, direct file synchronization protocols, and UI rendering hooks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelInitialImportStateTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val repository = FakeMemoRepository()
    private val sidebarStateHolder = MainSidebarStateHolder()
    private val appConfigRepository = FakeAppConfigRepository()
    private val appWidgetRepository = FakeAppWidgetRepository()
    private val imageMapProvider by lazy { emptyImageMapProvider() }
    private val audioPlayerManager by lazy { FakeAudioPlayerManager() }
    private val rootLocationFlow = MutableStateFlow<StorageLocation?>(null)
    private val switchRootStorageUseCase by lazy { FakeSwitchRootStorageUseCase(rootLocationFlow) }

    private lateinit var gitSyncRepo: GitSyncRepository
    private lateinit var mediaRepository: MediaRepository
    private lateinit var webDavSyncRepository: WebDavSyncRepository
    private lateinit var s3SyncRepository: S3SyncRepository
    private lateinit var syncInboxRepository: SyncInboxRepository
    private lateinit var syncPolicyRepository: SyncPolicyRepository
    private lateinit var appVersionRepository: AppVersionRepository
    private lateinit var memoVersionRepository: MemoVersionRepository

    private var appScope: CoroutineScope? = null

    init {
        extension(MainDispatcherExtension(testDispatcher))

        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.parse(any()) } answers {
            val uriStr = firstArg<String>()
            val mockUri = mockk<android.net.Uri>()
            every { mockUri.toString() } returns uriStr
            mockUri
        }

        beforeTest {
            appScope = CoroutineScope(SupervisorJob() + testDispatcher)

            gitSyncRepo = mockk()
            mediaRepository = mockk()
            webDavSyncRepository = mockk()
            s3SyncRepository = mockk()
            syncInboxRepository = mockk()
            syncPolicyRepository = mockk()
            appVersionRepository = mockk()
            memoVersionRepository = mockk()

            repository.setActiveMemos(emptyList())
            repository.resetCallCounts()
            appConfigRepository.setLocation(StorageArea.ROOT, null)
            rootLocationFlow.value = null
            switchRootStorageUseCase.reset()

            // Stub only the strictly required StartupCoordinator initialization queries
            every { gitSyncRepo.isGitSyncEnabled() } returns flowOf(false)
            every { gitSyncRepo.getSyncOnRefreshEnabled() } returns flowOf(false)
            every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(false)
            every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(false)
            every { s3SyncRepository.isS3SyncEnabled() } returns flowOf(false)
            every { s3SyncRepository.getSyncOnRefreshEnabled() } returns flowOf(false)
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.NONE)
            coEvery { appVersionRepository.getLastAppVersionOnce() } returns "1.0.0"
            coEvery { appVersionRepository.updateLastAppVersion(any()) } returns Unit
            coEvery { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) } returns
                UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
        }

        afterTest {
            settleMainDispatcher()
        }

        test("uiState is initial-importing while first refresh after root selection is running and no memos exist") {
            runTest {
                val refreshStarted = CompletableDeferred<Unit>()
                val allowRefreshToFinish = CompletableDeferred<Unit>()
                val refreshFinished = CompletableDeferred<Unit>()

                switchRootStorageUseCase.rebuildWorkspaceCallback = {
                    refreshStarted.complete(Unit)
                    allowRefreshToFinish.await()
                    refreshFinished.complete(Unit)
                }

                val viewModel = createViewModel()
                try {
                    advanceUntilIdle()
                    rootLocationFlow.value = StorageLocation("/tmp/large-root")
                    appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/large-root"))
                    runCurrent()

                    refreshStarted.await()

                    viewModel.uiState.value shouldBe MainViewModel.MainScreenState.InitialImporting

                    allowRefreshToFinish.complete(Unit)
                    refreshFinished.await()
                    advanceUntilIdle()
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }

        test("root change does not emit ready before initial-importing while first refresh is pending") {
            runTest {
                val refreshStarted = CompletableDeferred<Unit>()
                val allowRefreshToFinish = CompletableDeferred<Unit>()
                val refreshFinished = CompletableDeferred<Unit>()
                val observedStates = mutableListOf<MainViewModel.MainScreenState>()

                switchRootStorageUseCase.rebuildWorkspaceCallback = {
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
                        appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/large-root"))
                        runCurrent()
                        refreshStarted.await()

                        observedStates.contains(MainViewModel.MainScreenState.Ready) shouldBe false
                        viewModel.uiState.value shouldBe MainViewModel.MainScreenState.InitialImporting

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
        }

        test("uiState returns to ready after first refresh completes with empty memo list") {
            runTest {
                val refreshStarted = CompletableDeferred<Unit>()
                val allowRefreshToFinish = CompletableDeferred<Unit>()
                val refreshFinished = CompletableDeferred<Unit>()

                switchRootStorageUseCase.rebuildWorkspaceCallback = {
                    refreshStarted.complete(Unit)
                    allowRefreshToFinish.await()
                    refreshFinished.complete(Unit)
                }

                val viewModel = createViewModel()
                try {
                    advanceUntilIdle()
                    rootLocationFlow.value = StorageLocation("/tmp/large-root")
                    appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/large-root"))
                    refreshStarted.await()

                    allowRefreshToFinish.complete(Unit)
                    refreshFinished.await()
                    awaitUiState(viewModel, MainViewModel.MainScreenState.Ready)
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }

        test("uiState switches to initial-importing during root refresh when previous directory had memos") {
            runTest {
                repository.setActiveMemos(listOf(memo("memo-1", LocalDate.of(2026, 3, 31), 9)))
                rootLocationFlow.value = StorageLocation("/tmp/old-root")
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/old-root"))

                switchRootStorageUseCase.updateRootLocationCallback = { location ->
                    rootLocationFlow.value = location
                    appConfigRepository.setLocation(StorageArea.ROOT, location)
                    if (location.raw == "/tmp/new-root") {
                        awaitCancellation()
                    }
                }

                val viewModel = createViewModel()
                try {
                    advanceUntilIdle()
                    viewModel.onDirectorySelected("/tmp/new-root")
                    runCurrent()

                    awaitUiState(viewModel, MainViewModel.MainScreenState.InitialImporting)
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }

        test("populated-directory switch does not emit ready before initial-importing while refresh is pending") {
            runTest {
                val observedStates = mutableListOf<MainViewModel.MainScreenState>()
                repository.setActiveMemos(listOf(memo("memo-1", LocalDate.of(2026, 3, 31), 9)))
                rootLocationFlow.value = StorageLocation("/tmp/old-root")
                appConfigRepository.setLocation(StorageArea.ROOT, StorageLocation("/tmp/old-root"))

                switchRootStorageUseCase.updateRootLocationCallback = { location ->
                    rootLocationFlow.value = location
                    appConfigRepository.setLocation(StorageArea.ROOT, location)
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
                        awaitUiState(viewModel, MainViewModel.MainScreenState.InitialImporting)

                        observedStates.contains(MainViewModel.MainScreenState.Ready) shouldBe false
                        observedStates.firstOrNull() shouldBe MainViewModel.MainScreenState.InitialImporting
                    } finally {
                        collectJob.cancelAndJoin()
                    }
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }
    }

    private fun createViewModel(): MainViewModel =
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
                        createAppConfigStateProvider(),
                    audioPlayerManager = audioPlayerManager,
                ),
        )

    private fun createAppConfigStateProvider(): com.lomo.app.feature.common.AppConfigStateProvider =
        com.lomo.app.feature.common.AppConfigStateProvider(
            AppConfigUiCoordinator(appConfigRepository),
            appScope!!,
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

    private suspend fun awaitUiState(
        viewModel: MainViewModel,
        expected: MainViewModel.MainScreenState,
        timeoutMillis: Long = 5_000,
    ) {
        testDispatcher.scheduler.advanceUntilIdle()
        if (viewModel.uiState.value == expected) return
        
        kotlinx.coroutines.withTimeout(timeoutMillis) {
            viewModel.uiState.first { it == expected }
        }
    }

    private fun clearViewModel(viewModel: MainViewModel) {
        ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(viewModel)
        
        // Cancel appScope to cleanly close all StateFlow collections in AppConfigStateProvider
        appScope?.cancel()

        // Reflectively cancel the ImageMapProvider internal scope to clean up WhileSubscribed tasks
        runCatching {
            val field = ImageMapProvider::class.java.getDeclaredField("scope")
            field.isAccessible = true
            val scope = field.get(imageMapProvider) as CoroutineScope
            scope.cancel()
        }

        settleMainDispatcher()
    }

    private fun settleMainDispatcher() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    class FakeAppWidgetRepository : AppWidgetRepository(mockk()) {
        override suspend fun updateAllWidgets() {}
    }

    class FakeAudioPlayerManager : AudioPlayerManager(mockk(relaxed = true), mockk(relaxed = true)) {
        override fun play(uri: String) {}
        override fun seekTo(positionMs: Long) {}
        override fun pause() {}
        override fun stop() {}
        override fun release() {}
        override fun updateProgress() {}
    }

    class DummyDirectorySettingsRepository : DirectorySettingsRepository {
        override fun observeLocation(area: StorageArea) = TODO()
        override suspend fun currentLocation(area: StorageArea) = TODO()
        override suspend fun applyLocation(update: com.lomo.domain.model.StorageAreaUpdate) = TODO()
        override fun observeDisplayName(area: StorageArea) = TODO()
    }

    class DummyWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() = TODO()
    }

    class FakeSwitchRootStorageUseCase(
        private val rootLocationFlow: MutableStateFlow<StorageLocation?>
    ) : SwitchRootStorageUseCase(DummyDirectorySettingsRepository(), DummyWorkspaceStateResolver()) {
        var rebuildWorkspaceCallback: (suspend () -> Unit)? = null
        var updateRootLocationCallback: (suspend (StorageLocation) -> Unit)? = null

        fun reset() {
            rebuildWorkspaceCallback = null
            updateRootLocationCallback = null
        }

        override suspend fun rebuildCurrentWorkspace() {
            rebuildWorkspaceCallback?.invoke()
        }

        override suspend fun updateRootLocation(location: StorageLocation) {
            updateRootLocationCallback?.invoke(location) ?: run {
                rootLocationFlow.value = location
            }
        }
    }
}
