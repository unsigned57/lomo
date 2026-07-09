package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: ProviderSettingsController
 * - Owning layer: app/settings
 * - Priority tier: P2
 * - Capability: centralize remote provider enabled state, credential fields, interval state,
 *   sync state, connection-test lifecycle, and common actions for Git/WebDAV/S3 settings.
 *
 * Scenarios:
 * - Given provider-specific flows and credential declarations, when the controller model is
 *   collected, then one canonical model exposes common provider settings.
 * - Given common provider actions, when the controller mutates enabled/interval/manual sync,
 *   then the provider adapter actions execute through the shared path.
 * - Given a connection test succeeds or fails, when invoked through the controller, then one
 *   shared connection-test state model is updated without a provider-local state class.
 *
 * Observable outcomes:
 * - RemoteProviderSettingsModel values, captured action arguments, and RemoteProviderConnectionTestState transitions.
 *
 * TDD proof:
 * - RED: focused app test fails before the fix because ProviderSettingsController and the
 *   canonical RemoteProviderSettingsModel / RemoteProviderConnectionTestState types do not exist.
 *
 * Excludes:
 * - Domain sync engine behavior, repository-contract narrowing, and Compose rendering.
 */
class ProviderSettingsControllerTest : AppFunSpec() {
    init {
        test("given provider adapter state when model is collected then common settings use one canonical path") {
            runTest {
                val enabledFlow = MutableStateFlow(true)
                val autoSyncEnabledFlow = MutableStateFlow(false)
                val autoSyncIntervalFlow = MutableStateFlow("1h")
                val syncOnRefresh = MutableStateFlow(true)
                val lastSyncTimeFlow = MutableStateFlow(1234L)
                val credentials =
                    MutableStateFlow(
                        listOf(
                            RemoteProviderCredentialFieldState(
                                field = RemoteProviderCredentialField.S3AccessKeyId,
                                status = StoredCredentialStatus.Present,
                            ),
                        ),
                    )
                val rawSyncState = MutableStateFlow("CONNECTING")
                var updatedEnabled: Boolean? = null
                var updatedInterval: String? = null
                var manualSyncCount = 0

                val controller =
                    ProviderSettingsController(
                        provider = SyncBackendType.S3,
                        scope = backgroundScope,
                        enabled = enabledFlow,
                        autoSyncEnabled = autoSyncEnabledFlow,
                        autoSyncInterval = autoSyncIntervalFlow,
                        syncOnRefreshEnabled = syncOnRefresh,
                        lastSyncTime = lastSyncTimeFlow,
                        credentialFields = credentials,
                        rawSyncState = rawSyncState,
                        mapToUnifiedSyncState = { raw ->
                            if (raw == "CONNECTING") {
                                UnifiedSyncState.Running(SyncBackendType.S3, UnifiedSyncPhase.CONNECTING)
                            } else {
                                UnifiedSyncState.Idle
                            }
                        },
                        updateEnabledAction = { value -> updatedEnabled = value; null },
                        updateAutoSyncEnabledAction = { null },
                        updateAutoSyncIntervalAction = { value -> updatedInterval = value; null },
                        updateSyncOnRefreshEnabledAction = { null },
                        triggerSyncNowAction = { manualSyncCount += 1; null },
                        testConnectionAction = { RemoteProviderConnectionTestState.Success("connected") },
                        mapConnectionFailure = { throwable ->
                            RemoteProviderConnectionTestState.Error(
                                provider = SyncBackendType.S3,
                                providerCode = S3SyncErrorCode.UNKNOWN.name,
                                detail = throwable.message,
                            )
                        },
                    )
                val model =
                    controller.model.first { model ->
                        model.syncState == UnifiedSyncState.Running(SyncBackendType.S3, UnifiedSyncPhase.CONNECTING)
                    }

                controller.updateEnabled(false)
                controller.updateAutoSyncInterval("6h")
                controller.triggerSyncNow()

                assertSoftly(model) {
                    provider shouldBe SyncBackendType.S3
                    enabled shouldBe true
                    autoSyncEnabled shouldBe false
                    autoSyncInterval shouldBe "1h"
                    syncOnRefreshEnabled shouldBe true
                    lastSyncTime shouldBe 1234L
                    syncState shouldBe UnifiedSyncState.Running(SyncBackendType.S3, UnifiedSyncPhase.CONNECTING)
                    connectionTestState shouldBe RemoteProviderConnectionTestState.Idle
                    credentialFields shouldContain RemoteProviderCredentialFieldState(
                        field = RemoteProviderCredentialField.S3AccessKeyId,
                        status = StoredCredentialStatus.Present,
                    )
                }
                updatedEnabled shouldBe false
                updatedInterval shouldBe "6h"
                manualSyncCount shouldBe 1
            }
        }

        test("given connection test failure when invoked then shared provider error state is exposed") {
            runTest {
                val controller =
                    ProviderSettingsController(
                        provider = SyncBackendType.S3,
                        scope = backgroundScope,
                        enabled = MutableStateFlow(true),
                        autoSyncEnabled = MutableStateFlow(false),
                        autoSyncInterval = MutableStateFlow("1h"),
                        syncOnRefreshEnabled = MutableStateFlow(false),
                        lastSyncTime = MutableStateFlow(0L),
                        credentialFields = MutableStateFlow(emptyList()),
                        rawSyncState = MutableStateFlow(Unit),
                        mapToUnifiedSyncState = { UnifiedSyncState.Idle },
                        updateEnabledAction = { null },
                        updateAutoSyncEnabledAction = { null },
                        updateAutoSyncIntervalAction = { null },
                        updateSyncOnRefreshEnabledAction = { null },
                        triggerSyncNowAction = { null },
                        testConnectionAction = { throw IllegalStateException("network down") },
                        mapConnectionFailure = { throwable ->
                            RemoteProviderConnectionTestState.Error(
                                provider = SyncBackendType.S3,
                                providerCode = S3SyncErrorCode.CONNECTION_FAILED.name,
                                detail = throwable.message,
                            )
                        },
                    )

                val result = controller.testConnection()

                result shouldBe null
                controller.connectionTestState.value shouldBe RemoteProviderConnectionTestState.Error(
                    provider = SyncBackendType.S3,
                    providerCode = S3SyncErrorCode.CONNECTION_FAILED.name,
                    detail = "network down",
                )
                controller.resetConnectionTestState()
                controller.connectionTestState.value shouldBe RemoteProviderConnectionTestState.Idle
            }
        }

        test("given common provider actions when exposed then controller is the shared action target") {
            runTest {
                var updatedEnabled: Boolean? = null
                var updatedAutoSyncEnabled: Boolean? = null
                var updatedInterval: String? = null
                var updatedSyncOnRefresh: Boolean? = null
                var manualSyncCount = 0

                val actionTarget: RemoteProviderSettingsActionTarget =
                    ProviderSettingsController(
                        provider = SyncBackendType.GIT,
                        scope = backgroundScope,
                        enabled = MutableStateFlow(false),
                        autoSyncEnabled = MutableStateFlow(false),
                        autoSyncInterval = MutableStateFlow("1h"),
                        syncOnRefreshEnabled = MutableStateFlow(false),
                        lastSyncTime = MutableStateFlow(0L),
                        credentialFields = MutableStateFlow(emptyList()),
                        rawSyncState = MutableStateFlow(Unit),
                        mapToUnifiedSyncState = { UnifiedSyncState.Idle },
                        updateEnabledAction = { value -> updatedEnabled = value; null },
                        updateAutoSyncEnabledAction = { value -> updatedAutoSyncEnabled = value; null },
                        updateAutoSyncIntervalAction = { value -> updatedInterval = value; null },
                        updateSyncOnRefreshEnabledAction = { value -> updatedSyncOnRefresh = value; null },
                        triggerSyncNowAction = { manualSyncCount += 1; null },
                        testConnectionAction = { RemoteProviderConnectionTestState.Success("connected") },
                        mapConnectionFailure = { throwable ->
                            RemoteProviderConnectionTestState.Error(
                                provider = SyncBackendType.GIT,
                                providerCode = throwable::class.simpleName,
                                detail = throwable.message,
                            )
                        },
                    )

                actionTarget.updateEnabled(true)
                actionTarget.updateAutoSyncEnabled(true)
                actionTarget.updateAutoSyncInterval("6h")
                actionTarget.updateSyncOnRefreshEnabled(true)
                actionTarget.triggerSyncNow()
                actionTarget.testConnection()

                updatedEnabled shouldBe true
                updatedAutoSyncEnabled shouldBe true
                updatedInterval shouldBe "6h"
                updatedSyncOnRefresh shouldBe true
                manualSyncCount shouldBe 1
            }
        }
    }
}
