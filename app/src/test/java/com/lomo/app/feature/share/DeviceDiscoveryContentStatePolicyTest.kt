/*
 * Behavior Contract:
 * - Unit under test: device discovery content state policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: present LAN device discovery progress accurately while fallback probing continues.
 *
 * Scenarios:
 * - Given no devices and active fallback discovery is running, when NSD reports startup failure,
 *   then the device section remains in the searching state.
 * - Given no devices and no fallback discovery is running, when discovery reports startup failure,
 *   then the device section shows the startup-failed state.
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
 * - Fails before the fix because discovery-error presentation is embedded in Compose and treats
 *   every NSD startup failure as a hard failure even when active /share/ping probing continues.
 * - RED: targeted app test fails before the fix because permission-denied device UI hard-codes
 *   retry and settings actions instead of consuming the capability recovery plan.
 *
 * Excludes:
 * - live NSD, active HTTP probes, permission launcher behavior, and Compose rendering.
 */

package com.lomo.app.feature.share

import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class DeviceDiscoveryContentStatePolicyTest : AppFunSpec() {
    init {
        test("NSD failure stays searching while active fallback discovery continues") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 0,
                permissionState = LanSharePermissionState.Granted,
                discoveryError = "Failed to start device discovery",
                activeFallbackDiscovery = true,
            ) shouldBe DeviceDiscoveryContentState.Searching
        }

        test("NSD failure is shown when no fallback discovery is active") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 0,
                permissionState = LanSharePermissionState.Granted,
                discoveryError = "Failed to start device discovery",
                activeFallbackDiscovery = false,
            ) shouldBe DeviceDiscoveryContentState.StartupFailed
        }

        test("permission denial wins over fallback discovery") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 0,
                permissionState = LanSharePermissionState.Denied,
                discoveryError = "Failed to start device discovery",
                activeFallbackDiscovery = true,
            ) shouldBe DeviceDiscoveryContentState.PermissionDenied
        }

        test("devices win over discovery errors") {
            resolveDeviceDiscoveryContentState(
                discoveredDeviceCount = 1,
                permissionState = LanSharePermissionState.Granted,
                discoveryError = "Failed to start device discovery",
                activeFallbackDiscovery = false,
            ) shouldBe DeviceDiscoveryContentState.Devices
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
