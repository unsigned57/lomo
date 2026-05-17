package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: remote sync shared settings helpers in RemoteSyncSettingsShared.kt
 *
 * Scenario matrix:
 * - Happy: standard happy path for RemoteSyncSettingsSharedTest.
 * - Boundary: boundary and edge cases for RemoteSyncSettingsSharedTest.
 * - Failure: failure and error scenarios for RemoteSyncSettingsSharedTest.
 * - Must-not-happen: invariants are never violated for RemoteSyncSettingsSharedTest.
 * - Behavior focus: common remote sync settings observation, policy mutation wiring, and shared actions should
 *   preserve one backend-agnostic contract for Git/WebDAV/S3 settings orchestration.
 * - Observable outcomes: exposed flows, backend policy writes, auto-sync policy reapplication, and delegated
 *   manual-sync / connection-test results.
 * - Red phase: Fails before the fix because the shared settings helper layer does not exist yet.
 * - Excludes: repository transport behavior, provider-specific field mapping, and UI rendering.
 */
class RemoteSyncSettingsSharedTest : DomainFunSpec() {
    private val syncPolicyRepository: SyncPolicyRepository = io.mockk.mockk(relaxed = true)
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = io.mockk.mockk(relaxed = true)
    init {
        test("shared state observation exposes configured flows") {
            runTest {
                        val expectedState =
                            UnifiedSyncState.Running(
                                provider = SyncBackendType.GIT,
                                phase = UnifiedSyncPhase.PULLING,
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
    }
    init {
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
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        mutation.updateSyncEnabled(true)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.WEBDAV)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
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
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        mutation.updateAutoSyncEnabled(true)
                        mutation.updateAutoSyncInterval("1h")

                        autoSyncEnabled shouldBe true
                        autoSyncInterval shouldBe "1h"
                        coVerify(exactly = 2) { syncPolicyRepository.applyRemoteSyncPolicy() }
                    }
        }
    }
    init {
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
                        coVerify(exactly = 0) { syncPolicyRepository.applyRemoteSyncPolicy() }
                    }
        }
    }
    init {
        test("shared actions trigger manual sync and delegate connection test") {
            runTest {
                        val expected = GitSyncResult.Success("ok")
                        val actions =
                            RemoteSyncSharedActionsImpl<GitSyncResult>(
                                syncAndRebuildUseCase = syncAndRebuildUseCase,
                                connectionTester = { expected },
                            )
                        coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } returns Unit

                        actions.triggerSyncNow()
                        val result = actions.testConnection()

                        result shouldBe expected
                        coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(forceSync = true) }
                    }
        }
    }
}
