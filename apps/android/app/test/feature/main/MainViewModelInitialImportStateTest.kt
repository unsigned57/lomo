package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.app.testing.fakes.FakeWorkspaceMutationLease

import androidx.lifecycle.ViewModel
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.collectWhileSubscribed
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeAppRuntimeInfoRepository
import com.lomo.app.testing.fakes.FakeAppVersionRepository
import com.lomo.app.testing.fakes.FakeAudioPlayerManager
import com.lomo.app.testing.fakes.FakeExternalAppCommandStore
import com.lomo.app.testing.fakes.FakeGitSyncRepository
import com.lomo.app.testing.fakes.FakeMediaRepository
import com.lomo.app.testing.fakes.FakeMemoVersionRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.FakeS3SyncRepository
import com.lomo.app.testing.fakes.FakeSyncInboxRepository
import com.lomo.app.testing.fakes.FakeSyncPolicyRepository
import com.lomo.app.testing.fakes.FakeWebDavSyncRepository
import com.lomo.domain.usecase.SingleDispatcherProvider
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.GetCurrentAppBuildVersionUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * - Given a first workspace projection is Unavailable, when the ViewModel observes it, then UI reports
 *   OpeningEngine until that same projection becomes Verified.
 * - Given Recovery after a failed open, when the user retries, then activateWorkspace publishes a new
 *   generation instead of retryProjectionBuild.
 * - Given a root switch commits before its DataStore echo arrives, when the echo is observed, then
 *   the already-ready workspace does not re-enter an ownerless opening state.
 *
 * Observable outcomes:
 * - uiState StateFlow values over time during deferred import/rebuild operations.
 *
 * TDD proof:
 * - RED on 2026-08-16 because a late DataStore root echo set the local importing Boolean after
 *   the switch coroutine had already cleared it, leaving Main permanently InitialImporting.
 *
 * Excludes:
 * - Database writes, direct file synchronization protocols, and UI rendering hooks.
 *
 * Test Change Justification:
 * - Reason category: A02 mount freshness collapsed to Unavailable/Revalidating/Verified.
 * - Old behavior/assertion being replaced: Building/Failed freshness drove InitialImporting and a
 *   dedicated retryProjectionBuild path.
 * - Why old assertion is no longer correct: first projection without a trusted cache is Opening;
 *   failure is ReadOnlyRecovery; retry is a new activateWorkspace generation.
 * - Coverage preserved by: unavailable-until-verified, recovery retry, and late-root-echo states
 *   remain asserted at the user-visible MainScreenState boundary.
 * - Why this is not fitting the test to the implementation: the tests assert mount admission law, not
 *   a renamed importing Boolean.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelInitialImportStateTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val repository = FakeMemoStore()
    private val sidebarStateHolder = MainSidebarStateHolder()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider by lazy { emptyImageMapProvider() }
    private val audioPlayerManager by lazy { FakeAudioPlayerManager() }
    private val rootLocationFlow = MutableStateFlow<StorageLocation?>(null)
    private val dispatcherProvider = SingleDispatcherProvider(testDispatcher)
    private val engineReadinessRepository = com.lomo.app.testing.fakes.FakeEngineReadinessRepository()
    private val switchRootStorageUseCase by lazy {
        FakeSwitchRootStorageUseCase(rootLocationFlow, engineReadinessRepository)
    }
    private val workspaceMutationLease = FakeWorkspaceMutationLease(engineReadinessRepository)

    private lateinit var gitSyncRepo: FakeGitSyncRepository
    private lateinit var mediaRepository: FakeMediaRepository
    private lateinit var webDavSyncRepository: FakeWebDavSyncRepository
    private lateinit var s3SyncRepository: FakeS3SyncRepository
    private lateinit var syncInboxRepository: FakeSyncInboxRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var appRuntimeInfoRepository: FakeAppRuntimeInfoRepository
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
            appRuntimeInfoRepository = FakeAppRuntimeInfoRepository(currentVersionName = "1.0.0", currentVersionCode = 1L)
            appVersionRepository = FakeAppVersionRepository()
            memoVersionRepository = FakeMemoVersionRepository()

            repository.setActiveMemos(emptyList())
            repository.resetCallCounts()
            appConfigRepository.setLocation(StorageArea.ROOT, null)
            rootLocationFlow.value = null
            switchRootStorageUseCase.reset()
            appVersionRepository.lastAppVersion = "1.0.0"
            engineReadinessRepository.clearWorkspace()
        }

        afterTest {
            settleMainDispatcher()
        }

        test("first projection stays opening until its verified revision is readable") {
            runTest {
                val root = StorageLocation("/tmp/large-root")
                appConfigRepository.setLocation(StorageArea.ROOT, root)
                engineReadinessRepository.activateWorkspace(root)
                val revision = engineReadinessRepository.workspaceAuthority.value!!.projectionRevision
                engineReadinessRepository.publishProjectionFreshness(ProjectionFreshness.Unavailable)

                val viewModel = createViewModel()
                try {
                    advanceUntilIdle()
                    viewModel.uiState.value shouldBe MainViewModel.MainScreenState.OpeningEngine

                    engineReadinessRepository.publishProjectionFreshness(
                        ProjectionFreshness.Verified(revision),
                    )
                    awaitUiState(viewModel, MainViewModel.MainScreenState.Ready)
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }

        test("failed first projection is recoverable without recreating the ViewModel") {
            runTest {
                val root = StorageLocation("/tmp/large-root")
                appConfigRepository.setLocation(StorageArea.ROOT, root)
                engineReadinessRepository.activateWorkspace(root)
                engineReadinessRepository.publish(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.STORAGE,
                        code = "projection_refresh_failed",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "Workspace projection build failed",
                    ),
                )

                val viewModel = createViewModel()
                try {
                    advanceUntilIdle()
                    viewModel.uiState.value shouldBe
                        MainViewModel.MainScreenState.ReadOnlyRecovery(
                            code = "projection_refresh_failed",
                            diagnostic = "Workspace projection build failed",
                            canRebuildDerivedIndex = false,
                            hasRecoveryTarget = true,
                        )

                    viewModel.retryEngineOpen()
                    advanceUntilIdle()

                    viewModel.uiState.value shouldBe MainViewModel.MainScreenState.Ready
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }

        test("late persisted root echo cannot leave a completed switch permanently importing") {
            runTest {
                val oldRoot = StorageLocation("/tmp/old-root")
                val newRoot = StorageLocation("/tmp/new-root")
                rootLocationFlow.value = oldRoot
                appConfigRepository.setLocation(StorageArea.ROOT, oldRoot)
                engineReadinessRepository.activateWorkspace(oldRoot)
                switchRootStorageUseCase.updateRootLocationCallback = { location ->
                    engineReadinessRepository.activateWorkspace(location)
                    engineReadinessRepository.publishProjectionFreshness(
                        ProjectionFreshness.Unavailable,
                    )
                }

                val viewModel = createViewModel()
                try {
                    advanceUntilIdle()
                    viewModel.onDirectorySelected(newRoot.raw)
                    advanceUntilIdle()

                    appConfigRepository.setLocation(StorageArea.ROOT, newRoot)
                    viewModel.rootDirectory.first { directory -> directory == newRoot.raw }
                    val revision = engineReadinessRepository.workspaceAuthority.value!!.projectionRevision
                    engineReadinessRepository.publishProjectionFreshness(
                        ProjectionFreshness.Verified(revision),
                    )
                    runCurrent()

                    viewModel.uiState.value shouldBe MainViewModel.MainScreenState.Ready
                } finally {
                    clearViewModel(viewModel)
                }
            }
        }
    }

    private fun TestScope.createViewModel(): MainViewModel =
        MainViewModel(
            MainViewModelDependencies(
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
                memoUiMapper = testMemoUiMapper(),
                imageMapProvider = imageMapProvider,
                mainMemoMutationCoordinator =
                    MainMemoMutationCoordinator(
                        deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository)),
                        toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository(), com.lomo.app.testing.fakes.FakeMemoMutationRepository(repository)),
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
                        engineReadinessRepository = engineReadinessRepository,
                    ),
                startupCoordinator =
                    MainStartupCoordinator(
                        getCurrentAppBuildVersionUseCase = GetCurrentAppBuildVersionUseCase(appRuntimeInfoRepository),
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
                        observeWorkspaceSessionUseCase =
                            ObserveWorkspaceSessionUseCase(engineReadinessRepository),
                    ),
                markReminderDoneUseCase =
                    MarkReminderDoneUseCase(com.lomo.app.testing.fakes.FakeReminderCoordinator()),
                dispatcherProvider = dispatcherProvider,
                externalAppCommandStore = FakeExternalAppCommandStore(),
            ),
        ).also { viewModel ->
            collectWhileSubscribed(viewModel.uiState)
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
            customFontHost = com.lomo.app.testing.fakes.testCustomFontHost(com.lomo.app.testing.fakes.FakeCustomFontStore()),
            preferencesHealthRepository = com.lomo.app.testing.fakes.FakePreferencesHealthRepository(),
            appScope = appScope!!,
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
        override suspend fun prepareRootTransition(candidate: StorageLocation) = TODO()
        override suspend fun markRootTransitionActivated(transitionId: String) = TODO()
        override suspend fun commitRootTransition(transitionId: String) = TODO()
        override suspend fun rollbackRootTransition(transitionId: String) = TODO()
        override suspend fun pendingRootTransition() = TODO()
        override suspend fun recoverRootLocation() = TODO()
    }

    class DummyWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() = TODO()
    }

    class FakeSwitchRootStorageUseCase(
        private val rootLocationFlow: MutableStateFlow<StorageLocation?>,
        private val engineReadiness: com.lomo.domain.repository.EngineReadinessRepository,
    ) : SwitchRootStorageUseCase(DummyDirectorySettingsRepository(), DummyWorkspaceStateResolver(), FakeWorkspaceMutationLease(), com.lomo.app.testing.fakes.FakeEngineReadinessRepository()) {
        var updateRootLocationCallback: (suspend (StorageLocation) -> Unit)? = null

        fun reset() {
            updateRootLocationCallback = null
        }

        override suspend fun updateRootLocation(location: StorageLocation) {
            updateRootLocationCallback?.invoke(location) ?: run {
                rootLocationFlow.value = location
                engineReadiness.activateWorkspace(location)
            }
        }
    }
}
