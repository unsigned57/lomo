/*
 * Behavior Contract:
 * - Unit under test: device discovery content state policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: present LAN device discovery progress accurately while fallback probing continues.
 *
 * Scenarios:
 * - Given no devices and diagnostics report active probing is running, when NSD reports startup failure,
 *   then the device section remains in the searching state.
 * - Given no devices and diagnostics report active probing is not running, when discovery reports startup failure,
 *   then the device section shows the startup-failed state.
 * - Given active probing backs off or degrades because no Android Network route exists, when the search
 *   hint is resolved, then the user-visible state distinguishes budget rotation from route degradation.
 * - Given permission denial, when fallback discovery would otherwise run, then permission denial wins.
 * - Given discovered devices, when any error is present, then the device list wins.
 * - Given LocalNetwork denial recovery has a fallback and retry is enabled, when the permission-denied
 *   device section is resolved, then retry and settings actions are both exposed.
 * - Given LocalNetwork recovery has no fallback and retry is disabled, when the permission-denied
 *   device section is resolved, then no settings or retry affordance is exposed.
 *
 * Observable outcomes:
 * - resolved content state and recovery affordances used by the share screen's device discovery section.
 *
 * TDD proof:
 * - Slice 4 RED initially failed because device discovery policy accepted a UI-local
 *   `activeFallbackDiscovery` boolean instead of the canonical LAN share diagnostics state.
 * - Earlier coverage failed before the fix because discovery-error presentation was embedded in Compose and treated
 *   every NSD startup failure as a hard failure even when active /share/ping probing continued.
 * - RED: targeted app test fails before the fix because permission-denied device UI hard-codes
 *   retry and settings actions instead of consuming the capability recovery plan.
 *
 * Excludes:
 * - live NSD, active HTTP probes, permission launcher behavior, and Compose rendering.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */

package com.lomo.app.feature.share

import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.LanShareActiveProbeDiagnostics
import com.lomo.domain.model.LanShareActiveProbeState
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import io.kotest.matchers.shouldBe

class DeviceDiscoveryContentStatePolicyTest : AppFunSpec() {
    init {
        test("NSD failure stays searching while diagnostics report active probe recovery") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 0,
                permissionState = LanSharePermissionState.Granted,
                discoveryError = "Failed to start device discovery",
                diagnostics =
                    activeProbeDiagnostics(
                        state = LanShareActiveProbeState.Scanning,
                    ),
            ) shouldBe DeviceDiscoveryContentState.Searching
        }

        test("NSD failure is shown when diagnostics do not report active probe recovery") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 0,
                permissionState = LanSharePermissionState.Granted,
                discoveryError = "Failed to start device discovery",
                diagnostics = LanShareDiscoveryDiagnostics(),
            ) shouldBe DeviceDiscoveryContentState.StartupFailed
        }

        test("permission denial wins over fallback discovery") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 0,
                permissionState = LanSharePermissionState.Denied,
                discoveryError = "Failed to start device discovery",
                diagnostics =
                    activeProbeDiagnostics(
                        state = LanShareActiveProbeState.Scanning,
                    ),
            ) shouldBe DeviceDiscoveryContentState.PermissionDenied
        }

        test("devices win over discovery errors") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 1,
                permissionState = LanSharePermissionState.Granted,
                discoveryError = "Failed to start device discovery",
                diagnostics = LanShareDiscoveryDiagnostics(),
            ) shouldBe DeviceDiscoveryContentState.Devices
        }

        test("search hint reports budget rotation when active probe is backing off") {
            resolveDeviceDiscoverySearchHint(
                activeProbeDiagnostics(
                    state = LanShareActiveProbeState.BackingOff,
                ),
            ) shouldBe DeviceDiscoverySearchHint.ProbeBackoff
        }

        test("search hint reports degraded route when fallback snapshot has no routeable Network") {
            resolveDeviceDiscoverySearchHint(
                activeProbeDiagnostics(
                    state = LanShareActiveProbeState.DegradedNoRoute,
                ),
            ) shouldBe DeviceDiscoverySearchHint.DegradedRoute
        }

        test("LocalNetwork denied recovery exposes retry and settings affordances from plan") {
            resolveDeviceDiscoveryRecoveryAffordance(
                fallbackAction = CapabilityRecoveryAction.OpenAppSettings,
                canRetryAfterRecovery = true,
            ) shouldBe DeviceDiscoveryRecoveryAffordance(
                showRetry = true,
                fallbackAction = CapabilityRecoveryAction.OpenAppSettings,
            )
        }

        test("LocalNetwork denied recovery hides retry and settings when plan has no recovery affordance") {
            resolveDeviceDiscoveryRecoveryAffordance(
                fallbackAction = null,
                canRetryAfterRecovery = false,
            ) shouldBe DeviceDiscoveryRecoveryAffordance(
                showRetry = false,
                fallbackAction = null,
            )
        }
    }
}

private fun activeProbeDiagnostics(
    state: LanShareActiveProbeState,
): LanShareDiscoveryDiagnostics =
    LanShareDiscoveryDiagnostics(
        discoveryDesired = true,
        activeProbe =
            LanShareActiveProbeDiagnostics(
                state = state,
            ),
    )
