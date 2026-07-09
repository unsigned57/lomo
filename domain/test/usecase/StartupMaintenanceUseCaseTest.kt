package com.lomo.domain.usecase

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


import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeAppVersionRepository
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeMediaRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakePreferencesRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import com.lomo.domain.testing.fakes.FakeSyncInboxRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: StartupMaintenanceUseCase
 * - Capability: Safe cold-start optimization, deferred maintenance sequencing, cache warmups, and version-change cache invalidation/resync.
 * - Scenarios:
 *   - Given workspace config, when requesting root directory, then it maps and returns the raw root directory path.
 *   - Given an unchanged app version, when deferred startup runs, then it warms the image map cache and skips resync.
 *   - Given a startup request, when deferred startup runs, then it recreates the inbox directory structure before processing changes.
 *   - Given a changed app version but missing root directory, when deferred startup runs, then it updates version without full resync.
 *   - Given slow image cache warmup, when deferred startup runs, then it processes sync inbox changes concurrently without blocking.
 *   - Given required rebuild fails on version change, when deferred startup runs, then last processed version is not advanced.
 *   - Given generic sync inbox failures, when deferred startup runs, then it continues executing best-effort.
 *   - Given a sync inbox review, when deferred startup runs, then it leaves review resolution on the review route.
 * - Observable outcomes: call counts, state changes, version updates, thrown exceptions, and execution concurrency.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository implementation internals, filesystem or network integration, and UI rendering.
 */
class StartupMaintenanceUseCaseTest : DomainFunSpec() {
    /*
     * Test Change Justification:
     * - Reason category: test framework migration & optimization
     * - Replaced assertion: Removed all coVerify/verify MockK checks.
     * - Previous assertion is no longer correct because we migrated completely to pure state-based fakes.
     * - Retained coverage: All BDD scenarios and assertions on observable side-effects are completely preserved.
     * - Why this is not changing the test to fit the implementation: The contract of StartupMaintenanceUseCase remains exactly as specified.
     */
    init {
        test("initializeRootDirectory returns raw location from workspace use case") {
            runTest {
                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val syncInboxRepository = FakeSyncInboxRepository()
                val preferencesRepository = FakePreferencesRepository()
                val appVersionRepository = FakeAppVersionRepository()

                directorySettingsRepository.setLocation(StorageArea.ROOT, StorageLocation("/memo-root"))

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                val root = useCase.initializeRootDirectory()

                root shouldBe "/memo-root"
            }
        }

        test("runDeferredStartupTasks warms image cache and skips resync when version unchanged") {
            runTest {
                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val syncInboxRepository = FakeSyncInboxRepository()
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.1")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                syncInboxRepository.syncRequests shouldBe listOf(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                mediaRepository.refreshImageLocationsCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 0
                appVersionRepository.updatedVersions.size shouldBe 0
            }
        }

        test("runDeferredStartupTasks recreates sync inbox directories before processing inbox changes") {
            runTest {
                val eventLog = mutableListOf<String>()
                val delegateInbox = FakeSyncInboxRepository()
                val syncInboxRepository = object : SyncInboxRepository {
                    override fun syncState(): Flow<UnifiedSyncState> = delegateInbox.syncState()

                    override suspend fun ensureDirectoryStructure() {
                        delegateInbox.ensureDirectoryStructure()
                        eventLog += "ensureDirectoryStructure"
                    }

                    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
                        eventLog += "sync:$operation"
                        return delegateInbox.sync(operation)
                    }

                    override suspend fun resolveReview(
                        resolution: SyncReviewResolution,
                        review: SyncReviewSession,
                    ): UnifiedSyncResult {
                        return delegateInbox.resolveReview(resolution, review)
                    }
                }

                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.1")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                eventLog shouldBe listOf(
                    "ensureDirectoryStructure",
                    "sync:PROCESS_PENDING_CHANGES",
                )
            }
        }

        test("runDeferredStartupTasks updates version without rebuild when root is missing") {
            runTest {
                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val syncInboxRepository = FakeSyncInboxRepository()
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.0")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                useCase.runDeferredStartupTasks(rootDir = null, currentVersion = "0.9.1")

                syncInboxRepository.syncRequests shouldBe listOf(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                mediaRepository.refreshImageLocationsCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 0
                appVersionRepository.lastAppVersion shouldBe "0.9.1"
                appVersionRepository.updatedVersions shouldBe listOf("0.9.1")
            }
        }

        test("runDeferredStartupTasks starts inbox processing while image warm up is still running") {
            runTest {
                val imageWarmStarted = CompletableDeferred<Unit>()
                val releaseImageWarm = CompletableDeferred<Unit>()
                val delegateMedia = FakeMediaRepository()
                val mediaRepository = object : MediaRepository {
                    override suspend fun importImage(source: StorageLocation) = delegateMedia.importImage(source)
                    override suspend fun removeImage(entryId: MediaEntryId) = delegateMedia.removeImage(entryId)
                    override fun observeImageLocations() = delegateMedia.observeImageLocations()
                    override suspend fun ensureCategoryWorkspace(category: MediaCategory) = delegateMedia.ensureCategoryWorkspace(category)
                    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId) = delegateMedia.allocateVoiceCaptureTarget(entryId)
                    override suspend fun removeVoiceCapture(entryId: MediaEntryId) = delegateMedia.removeVoiceCapture(entryId)

                    override suspend fun refreshImageLocations() {
                        delegateMedia.refreshImageLocations()
                        imageWarmStarted.complete(Unit)
                        releaseImageWarm.await()
                    }
                }

                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val syncInboxRepository = FakeSyncInboxRepository()
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.1")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                val deferredStartup = async {
                    useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")
                }

                imageWarmStarted.await()
                syncInboxRepository.syncRequests shouldBe listOf(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)

                releaseImageWarm.complete(Unit)
                deferredStartup.await()
            }
        }

        test("runDeferredStartupTasks does not advance version when required version-change rebuild fails") {
            runTest {
                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val delegatePolicy = FakeSyncPolicyRepository()
                val syncPolicyRepository = object : SyncPolicyRepository {
                    override fun ensureCoreSyncActive() = delegatePolicy.ensureCoreSyncActive()
                    override suspend fun setRemoteSyncBackend(type: SyncBackendType) = delegatePolicy.setRemoteSyncBackend(type)
                    override suspend fun applyRemoteSyncPolicy() = delegatePolicy.applyRemoteSyncPolicy()

                    override fun observeRemoteSyncBackend(): Flow<SyncBackendType> {
                        throw IllegalStateException("sync failed")
                    }
                }
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val syncInboxRepository = FakeSyncInboxRepository()
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.0")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                shouldThrow<IllegalStateException> {
                    useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")
                }

                syncInboxRepository.syncRequests shouldBe listOf(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                mediaRepository.refreshImageLocationsCallCount shouldBe 1
                appVersionRepository.lastAppVersion shouldBe "0.9.0"
                appVersionRepository.updatedVersions shouldBe emptyList()
            }
        }

        test("runDeferredStartupTasks keeps progressing when sync inbox import fails generically") {
            runTest {
                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val delegateInbox = FakeSyncInboxRepository()
                val syncInboxRepository = object : SyncInboxRepository {
                    override fun syncState() = delegateInbox.syncState()
                    override suspend fun ensureDirectoryStructure() = delegateInbox.ensureDirectoryStructure()
                    override suspend fun resolveReview(resolution: SyncReviewResolution, review: SyncReviewSession) =
                        delegateInbox.resolveReview(resolution, review)

                    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
                        throw IllegalStateException("inbox failed")
                    }
                }
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.1")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                mediaRepository.refreshImageLocationsCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 0
                appVersionRepository.updatedVersions.size shouldBe 0
            }
        }

        test("runDeferredStartupTasks treats sync inbox review as user action instead of conflict failure") {
            runTest {
                val review =
                    SyncReviewSession(
                        source = SyncBackendType.INBOX,
                        items =
                            listOf(
                                SyncReviewItem(
                                    relativePath = "inbox/2026_04_13.md",
                                    localContent = "local",
                                    incomingContent = "incoming",
                                    isBinary = false,
                                ),
                            ),
                        timestamp = 123L,
                        kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
                    )
                val directorySettingsRepository = FakeDirectorySettingsRepository()
                val mediaRepository = FakeMediaRepository()
                val initializeWorkspaceUseCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
                val memoRepository = FakeMemoStore()
                val syncPolicyRepository = FakeSyncPolicyRepository()
                val syncAndRebuildUseCase = SyncAndRebuildUseCase(
                    memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
                    syncProviderRegistry = SyncProviderRegistry(emptySet()),
                    syncPolicyRepository = syncPolicyRepository,
                )
                val syncInboxRepository =
                    FakeSyncInboxRepository().apply {
                        nextSyncResult =
                            UnifiedSyncResult.Review(
                                provider = SyncBackendType.INBOX,
                                message = "Sync inbox review required",
                                review = review,
                            )
                    }
                val preferencesRepository = FakePreferencesRepository().apply {
                    setSyncInboxPreferenceEnabled(true)
                }
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "0.9.1")

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                            InboxUnifiedSyncProvider(syncInboxRepository, preferencesRepository)
                        )
                    ),
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                syncInboxRepository.syncRequests shouldBe listOf(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                mediaRepository.refreshImageLocationsCallCount shouldBe 1
                appVersionRepository.updatedVersions.size shouldBe 0
            }
        }

    }
}
