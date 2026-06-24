package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeAppVersionRepository
import com.lomo.app.testing.fakes.FakeAppWidgetRepository
import com.lomo.app.testing.fakes.FakeAudioPlayerManager
import com.lomo.app.testing.fakes.FakeGitSyncRepository
import com.lomo.app.testing.fakes.FakeMediaRepository
import com.lomo.app.testing.fakes.FakeMemoVersionRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.FakeS3SyncRepository
import com.lomo.app.testing.fakes.FakeSyncInboxRepository
import com.lomo.app.testing.fakes.FakeSyncPolicyRepository
import com.lomo.app.testing.fakes.FakeWebDavSyncRepository
import com.lomo.domain.usecase.FakeDispatcherProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.S3UnifiedSyncProvider
import com.lomo.domain.usecase.SetMemoPinnedUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.WebDavUnifiedSyncProvider
import io.kotest.matchers.shouldBe
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MainViewModel initial import and directory switching state.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Main screen loading and directory switching state orchestration.
 *
 * Scenarios:
 * - Given a workspace rebuild starts on a new empty directory, when the ViewModel observes the rebuild, then UI reports InitialImporting until complete.
 * - Given a root directory change occurs, when the ViewModel transitions state, then Ready state is not flashed prematurely before InitialImporting starts.
 * - Given a workspace rebuild finishes, when the ViewModel observes completion, then UI state transitions cleanly to Ready.
 *
 * Observable outcomes:
 * - uiState StateFlow values over time during deferred import/rebuild operations.
 *
 * TDD proof:
 * - Fails before the fix because lifecycle status management during asynchronous I/O background refreshes was not robustly observed.
 *
 * Excludes:
 * - Database writes, direct file synchronization protocols, and UI rendering hooks.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelInitialImportStateTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val repository = FakeMemoStore()
    private val sidebarStateHolder = MainSidebarStateHolder()
    private val appConfigRepository = FakeAppConfigRepository()
    private val appWidgetRepository = FakeAppWidgetRepository()
    private val imageMapProvider by lazy { emptyImageMapProvider() }
    private val audioPlayerManager by lazy { FakeAudioPlayerManager() }
    private val rootLocationFlow = MutableStateFlow<StorageLocation?>(null)
    private val switchRootStorageUseCase by lazy { FakeSwitchRootStorageUseCase(rootLocationFlow) }
    private val dispatcherProvider = FakeDispatcherProvider(testDispatcher)

    private lateinit var gitSyncRepo: FakeGitSyncRepository
    private lateinit var mediaRepository: FakeMediaRepository
    private lateinit var webDavSyncRepository: FakeWebDavSyncRepository
    private lateinit var s3SyncRepository: FakeS3SyncRepository
    private lateinit var syncInboxRepository: FakeSyncInboxRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var appVersionRepository: FakeAppVersionRepository
    private lateinit var memoVersionRepository: FakeMemoVersionRepository

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

            gitSyncRepo = FakeGitSyncRepository()
            mediaRepository = FakeMediaRepository()
            webDavSyncRepository = FakeWebDavSyncRepository()
            s3SyncRepository = FakeS3SyncRepository()
            syncInboxRepository = FakeSyncInboxRepository()
            syncPolicyRepository = FakeSyncPolicyRepository()
            appVersionRepository = FakeAppVersionRepository()
            memoVersionRepository = FakeMemoVersionRepository()

            repository.setActiveMemos(emptyList())
            repository.resetCallCounts()
            appConfigRepository.setLocation(StorageArea.ROOT, null)
            rootLocationFlow.value = null
            switchRootStorageUseCase.reset()
            appVersionRepository.lastAppVersion = "1.0.0"
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
            mainMemoListQueryUseCase = mainMemoListQueryUseCase(),
            observeActiveDayCountUseCase = observeActiveDayCountUseCase(),
            setMemoPinnedUseCase = setMemoPinnedUseCase(),
            appConfigStateProvider = createAppConfigStateProvider(),
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
            memoUiMapper = MemoUiMapper(),
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
                        createAppConfigStateProvider(),
                    audioPlayerManager = audioPlayerManager,
                ),
            markReminderDoneUseCase =
                MarkReminderDoneUseCase(com.lomo.app.testing.fakes.FakeReminderCoordinator()),
            dispatcherProvider = dispatcherProvider,
        )

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
            appScope = appScope!!,
        )

    private fun syncProviderRegistry(): SyncProviderRegistry =
        SyncProviderRegistry(
            providers =
                listOf(
                    GitUnifiedSyncProvider(gitSyncRepo),
                    WebDavUnifiedSyncProvider(webDavSyncRepository),
                    S3UnifiedSyncProvider(s3SyncRepository),
                    InboxUnifiedSyncProvider(syncInboxRepository, appConfigRepository),
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
