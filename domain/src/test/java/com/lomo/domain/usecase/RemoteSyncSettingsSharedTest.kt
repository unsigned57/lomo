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


import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import com.lomo.domain.testing.fakes.FakeUnifiedSyncProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: remote sync shared settings helpers in RemoteSyncSettingsShared.kt
 * - Capability: Backend-agnostic settings orchestration for Git/WebDAV/S3 remote synchronization.
 * - Scenarios:
 *   - Given a configured RemoteSyncSharedStateObservation, when observing sync properties, then it should expose the flows correctly.
 *   - Given a RemoteSyncSharedMutation, when updating sync enabled, then it updates backend type and reapplies policy.
 *   - Given a RemoteSyncSharedMutation, when updating auto-sync properties, then it updates through provider and reapplies policy.
 *   - Given a RemoteSyncSharedMutation, when updating sync on refresh, then it updates without reapplying policy.
 *   - Given a RemoteSyncSharedActions with real SyncAndRebuildUseCase, when triggerSyncNow is called, then it triggers manual sync on the active provider.
 * - Observable outcomes: exposed flows, backend policy writes, auto-sync policy reapplication, and delegated manual-sync/connection-test results.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository transport behavior, provider-specific field mapping, and UI rendering.
 */
class RemoteSyncSettingsSharedTest : DomainFunSpec() {
    private val syncPolicyRepository = FakeSyncPolicyRepository(initialBackend = SyncBackendType.GIT)
    private val memoRepository = FakeMemoStore()
    private val fakeGitProvider = FakeUnifiedSyncProvider(backendType = SyncBackendType.GIT)
    private val fakeInboxProvider = FakeUnifiedSyncProvider(backendType = SyncBackendType.INBOX)
    private val syncProviderRegistry = SyncProviderRegistry(
        providers = listOf(fakeGitProvider, fakeInboxProvider)
    )
    private val syncAndRebuildUseCase = SyncAndRebuildUseCase(
        memoRepository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository),
        syncProviderRegistry = syncProviderRegistry,
        syncPolicyRepository = syncPolicyRepository,
    )

    init {
        test("shared state observation exposes configured flows") {
            runTest {
                val expectedState =
                    UnifiedSyncState.Running(
                        provider = SyncBackendType.GIT,
                        phase = com.lomo.domain.model.UnifiedSyncPhase.PULLING,
                    )
                val observation =
                    RemoteSyncSharedStateObservationImpl(
                        enabled = { flowOf(true) },
                        autoSyncEnabled = { flowOf(false) },
                        autoSyncInterval = { flowOf("30m") },
                        syncOnRefreshEnabled = { flowOf(true) },
                        lastSyncTimeMillis = { flowOf(1234L) },
                        syncState = { flowOf(expectedState) },
                    )

                observation.observeSyncEnabled().first() shouldBe true
                observation.observeAutoSyncEnabled().first() shouldBe false
                observation.observeAutoSyncInterval().first() shouldBe "30m"
                observation.observeSyncOnRefreshEnabled().first() shouldBe true
                observation.observeLastSyncTimeMillis().first() shouldBe 1234L
                observation.observeSyncState().first() shouldBe expectedState
            }
        }

        test("shared mutation updates backend policy and reapplies policy") {
            runTest {
                val mutation =
                    RemoteSyncSharedMutationImpl(
                        backendType = SyncBackendType.WEBDAV,
                        syncPolicyRepository = syncPolicyRepository,
                        autoSyncEnabledUpdater = {},
                        autoSyncIntervalUpdater = {},
                        syncOnRefreshUpdater = {},
                    )

                mutation.updateSyncEnabled(true)

                syncPolicyRepository.setBackendRequests shouldBe listOf(SyncBackendType.WEBDAV)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 1
            }
        }

        test("shared mutation reuses provider updater and reapplies policy for auto sync changes") {
            runTest {
                var autoSyncEnabled: Boolean? = null
                var autoSyncInterval: String? = null
                val mutation =
                    RemoteSyncSharedMutationImpl(
                        backendType = SyncBackendType.S3,
                        syncPolicyRepository = syncPolicyRepository,
                        autoSyncEnabledUpdater = { autoSyncEnabled = it },
                        autoSyncIntervalUpdater = { autoSyncInterval = it },
                        syncOnRefreshUpdater = {},
                    )

                mutation.updateAutoSyncEnabled(true)
                mutation.updateAutoSyncInterval("1h")

                autoSyncEnabled shouldBe true
                autoSyncInterval shouldBe "1h"
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
            }
        }

        test("shared mutation updates sync-on-refresh without reapplying policy") {
            runTest {
                var syncOnRefresh: Boolean? = null
                val mutation =
                    RemoteSyncSharedMutationImpl(
                        backendType = SyncBackendType.GIT,
                        syncPolicyRepository = syncPolicyRepository,
                        autoSyncEnabledUpdater = {},
                        autoSyncIntervalUpdater = {},
                        syncOnRefreshUpdater = { syncOnRefresh = it },
                    )

                mutation.updateSyncOnRefreshEnabled(true)

                syncOnRefresh shouldBe true
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 0
            }
        }

        test("shared actions trigger manual sync and delegate connection test") {
            runTest {
                val expected = GitSyncResult.Success("ok")
                val actions =
                    RemoteSyncSharedActionsImpl<GitSyncResult>(
                        syncAndRebuildUseCase = syncAndRebuildUseCase,
                        connectionTester = { expected },
                    )

                actions.triggerSyncNow()
                val result = actions.testConnection()

                result shouldBe expected
                fakeGitProvider.syncRequests shouldContain UnifiedSyncOperation.MANUAL_SYNC
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }
    }
}
