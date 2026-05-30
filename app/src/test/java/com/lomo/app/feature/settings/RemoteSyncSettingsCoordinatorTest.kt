package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncSettingsCoordinator
 * - Owning layer: app/settings
 * - Priority tier: P1
 * - Capability: centralize shared remote sync settings interaction state/actions across Git/WebDAV/S3.
 *
 * Scenarios:
 * - Given provider state flow and shared mutators, when coordinator is created, then shared state mirrors it.
 * - Given a provider-specific raw sync state, when unified state mapping is configured, then mapped state is exposed from one shared owner.
 * - Given manual sync and connection test actions, when invoked through shared coordinator, then provider actions execute.
 *
 * Observable outcomes:
 * - StateFlow values for enabled/auto-sync/interval/sync-on-refresh/last-sync/unified-sync-state.
 * - Invoked counters for update/mutation/test/manual-sync delegates.
 *
 * TDD proof:
 * - RED: this test does not compile before RemoteSyncSettingsCoordinator exists.
 *
 * Excludes:
 * - Provider-specific connection form fields, Compose rendering, and domain repository internals.
 */
class RemoteSyncSettingsCoordinatorTest : AppFunSpec() {
    init {
        test("shared coordinator exposes mapped unified sync state and shared actions") {
            runTest {
                val enabled = MutableStateFlow(false)
                val autoSyncEnabled = MutableStateFlow(false)
                val autoSyncInterval = MutableStateFlow("30m")
                val syncOnRefresh = MutableStateFlow(false)
                val lastSyncTime = MutableStateFlow(0L)
                val rawSyncState = MutableStateFlow("RUNNING")
                var updateEnabledArg: Boolean? = null
                var triggerCount = 0
                var testCount = 0

                val coordinator =
                    RemoteSyncSettingsCoordinator(
                        enabled = enabled,
                        autoSyncEnabled = autoSyncEnabled,
                        autoSyncInterval = autoSyncInterval,
                        syncOnRefreshEnabled = syncOnRefresh,
                        lastSyncTime = lastSyncTime,
                        rawSyncState = rawSyncState,
                        mapToUnifiedSyncState = { raw ->
                            if (raw == "RUNNING") {
                                UnifiedSyncState.Running(SyncBackendType.WEBDAV, UnifiedSyncPhase.CONNECTING)
                            } else {
                                UnifiedSyncState.Idle
                            }
                        },
                        updateEnabledAction = { value -> updateEnabledArg = value; null },
                        updateAutoSyncEnabledAction = { null },
                        updateAutoSyncIntervalAction = { null },
                        updateSyncOnRefreshEnabledAction = { null },
                        triggerSyncNowAction = { triggerCount += 1; null },
                        testConnectionAction = { testCount += 1; null },
                    )

                coordinator.syncState.first() shouldBe UnifiedSyncState.Running(SyncBackendType.WEBDAV, UnifiedSyncPhase.CONNECTING)
                coordinator.updateEnabled(true)
                coordinator.triggerSyncNow()
                coordinator.testConnection()

                updateEnabledArg shouldBe true
                triggerCount shouldBe 1
                testCount shouldBe 1
            }
        }
    }
}
