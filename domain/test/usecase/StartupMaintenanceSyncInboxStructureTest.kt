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


import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeAppVersionRepository
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeMediaRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakePreferencesRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: StartupMaintenanceUseCase
 * - Capability: Guarantee recreation of sync-inbox directory structure before processing inbox files on startup.
 * - Scenarios:
 *   - Given a startup request, when running deferred startup tasks, then it first ensures the sync inbox directories are created, and only then processes pending inbox sync changes.
 * - Observable outcomes: Ordering of ensureDirectoryStructure and UnifiedSyncOperation.PROCESS_PENDING_CHANGES.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: filesystem creation details, cache warm-up behavior, and conflict dialog handling.
 */
class StartupMaintenanceSyncInboxStructureTest : DomainFunSpec() {
    init {
        test("runDeferredStartupTasks recreates sync inbox directories before processing inbox changes") {
            runTest {
                val eventLog = mutableListOf<String>()
                val syncInboxRepository = object : SyncInboxRepository {
                    override fun syncState(): Flow<UnifiedSyncState> = flowOf(UnifiedSyncState.Idle)

                    override suspend fun ensureDirectoryStructure() {
                        eventLog += "ensureDirectoryStructure"
                    }

                    override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
                        eventLog += "sync:$operation"
                        return UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
                    }

                    override suspend fun resolveReview(
                        resolution: SyncReviewResolution,
                        review: SyncReviewSession,
                    ): UnifiedSyncResult {
                        return UnifiedSyncResult.Success(SyncBackendType.INBOX, "resolved")
                    }
                }

                val mediaRepository = FakeMediaRepository()
                val directorySettingsRepository = FakeDirectorySettingsRepository()
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
                val appVersionRepository = FakeAppVersionRepository(lastAppVersion = "1.0.0")
                val syncProviderRegistry = SyncProviderRegistry(
            providers =
                setOf(
                        InboxUnifiedSyncProvider(
                            syncInboxRepository = syncInboxRepository,
                            preferencesRepository = preferencesRepository,
                        ),
                    ),
                )

                val useCase = StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = syncProviderRegistry,
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

                useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "1.0.0")

                eventLog shouldBe listOf(
                    "ensureDirectoryStructure",
                    "sync:PROCESS_PENDING_CHANGES",
                )
            }
        }
    }
}
