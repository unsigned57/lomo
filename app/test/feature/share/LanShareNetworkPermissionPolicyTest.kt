/*
 * Behavior Contract:
 * - Unit under test: LanShareNetworkPermissionPolicy.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: derive LAN sharing runtime permission behavior from the shared capability catalog.
 *
 * Scenarios:
 * - Given API 32, when LAN sharing asks for required permissions, then no runtime permission is needed.
 * - Given API 33 through 35, when LAN sharing asks for required permissions, then no runtime permission
 *   is requested because NSD and bound LAN HTTP do not use nearby-Wi-Fi APIs.
 * - Given API 36+, when ACCESS_LOCAL_NETWORK is recognized, then LAN sharing requests only that
 *   platform local-network permission.
 * - Given API 36+ when ACCESS_LOCAL_NETWORK is not recognized, then LAN sharing omits that permission.
 * - Given a permission callback, when all required permissions are granted or current state is already
 *   granted, then LAN sharing proceeds.
 * - Given denial or permanent denial, when recovery is evaluated, then LAN sharing exposes app settings
 *   fallback with retry enabled.
 * - Given LocalNetwork denied recovery, when the app-layer recovery executor receives the fallback,
 *   then it opens app settings.
 * - Given no fallback action, when the recovery executor is asked to run recovery, then it performs
 *   no settings action.
 *
 * Observable outcomes:
 * - Permission name lists, grant aggregation result, fallback recovery action, and executed recovery callback.
 *
 * TDD proof:
 * - RED: before the fix, SDK 33-35 returned NEARBY_WIFI_DEVICES and SDK 36 returned both permissions,
 *   violating the platform API contract for the NSD and LAN HTTP APIs actually used by this feature.
 *
 * Excludes:
 * - Compose launcher wiring, Android permission dialog rendering, localized denial copy.
 *
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: asserting NearbyWifiDevices is required on API 33-35, and both permissions on API 36+.
 * - Why old assertion is no longer correct: NSD and bound LAN HTTP APIs do not require NearbyWifiDevices.
 * - Coverage preserved by: asserting that API 33-35 requires no permissions, and API 36+ requires only ACCESS_LOCAL_NETWORK.
 * - Why this is not fitting the test to the implementation: It aligns with actual platform API requirements.
 */

package com.lomo.app.feature.share

import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.CapabilityRecoveryDecision
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private const val SDK_TIRAMISU = 33
private const val SDK_UPSIDE_DOWN_CAKE = 34
private const val SDK_VANILLA_ICE_CREAM = 35
private const val SDK_BAKLAVA = 36
private const val SDK_BAKLAVA_PLUS_ONE = 37

private val PermissionAlwaysRecognized: LanSharePermissionRecognizer = { true }
private val PermissionNeverRecognized: LanSharePermissionRecognizer = { false }

class LanShareNetworkPermissionPolicyTest : AppFunSpec() {
    init {
        test("API 32 requires no LAN share network permissions") {
            requiredLanShareNetworkPermissions(
                sdkInt = 32,
                isPermissionRecognized = PermissionAlwaysRecognized,
            ) shouldBe emptyList()
        }

        test("API 33 (Tiramisu) requires no LAN share runtime permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_TIRAMISU,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldBe emptyList()
        }

        test("API 34 (UpsideDownCake) requires no LAN share runtime permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_UPSIDE_DOWN_CAKE,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldBe emptyList()
        }

        test("API 35 (VanillaIceCream) requires no LAN share runtime permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_VANILLA_ICE_CREAM,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldBe emptyList()
        }

        test("API 36 (Baklava) requests ACCESS_LOCAL_NETWORK only when system recognizes the permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions.shouldContainExactly(ACCESS_LOCAL_NETWORK_PERMISSION)
        }

        test("API 36 skips ACCESS_LOCAL_NETWORK when system does not recognize the permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA,
                    isPermissionRecognized = PermissionNeverRecognized,
                )

            permissions shouldBe emptyList()
        }

        test("API 37+ still gates ACCESS_LOCAL_NETWORK on system recognition") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA_PLUS_ONE,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions.shouldContainExactly(ACCESS_LOCAL_NETWORK_PERMISSION)
        }

        test("required LAN share permissions are derived from the shared capability catalog") {
            val sdk36Permissions =
                lanShareNetworkPermissionPlan().requiredPermissions(
                    sdkInt = SDK_BAKLAVA,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            sdk36Permissions shouldContainExactly
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )
        }

        test("permission request is granted when all required permissions return true") {
            val required = listOf(ACCESS_LOCAL_NETWORK_PERMISSION)

            val granted = isLanSharePermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(ACCESS_LOCAL_NETWORK_PERMISSION to true),
                hasCurrentPermissions = false,
            )

            granted shouldBe true
        }

        test("permission request falls back to current grant state when callback misses entries") {
            val required = listOf(ACCESS_LOCAL_NETWORK_PERMISSION)

            val granted = isLanSharePermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = emptyMap(),
                hasCurrentPermissions = true,
            )

            granted shouldBe true
        }

        test("permission request fails when a required permission is denied and OS doesn't already grant it") {
            val required = listOf(ACCESS_LOCAL_NETWORK_PERMISSION)

            val granted = isLanSharePermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(ACCESS_LOCAL_NETWORK_PERMISSION to false),
                hasCurrentPermissions = false,
            )

            granted shouldBe false
        }

        test("permission recovery opens app settings after denial and permanent denial") {
            val deniedRecovery = lanSharePermissionRecoveryAction(CapabilityRecoveryDecision.Denied)
            val permanentlyDeniedRecovery =
                lanSharePermissionRecoveryAction(CapabilityRecoveryDecision.PermanentlyDenied)

            deniedRecovery shouldBe CapabilityRecoveryAction.OpenAppSettings
            permanentlyDeniedRecovery shouldBe CapabilityRecoveryAction.OpenAppSettings
            canRetryLanSharePermissionRecovery() shouldBe true
        }

        test("LocalNetwork denied recovery executes the app settings callback") {
            var settingsOpenCount = 0
            val executor =
                CapabilityRecoveryExecutor(
                    onOpenAppSettings = { settingsOpenCount += 1 },
                )

            val executed =
                executor.execute(lanSharePermissionRecoveryAction(CapabilityRecoveryDecision.Denied))

            executed shouldBe true
            settingsOpenCount shouldBe 1
        }

        test("missing LocalNetwork fallback recovery does not execute app settings") {
            var settingsOpenCount = 0
            val executor =
                CapabilityRecoveryExecutor(
                    onOpenAppSettings = { settingsOpenCount += 1 },
                )

            val executed = executor.execute(null)

            executed shouldBe false
            settingsOpenCount shouldBe 0
        }
    }
}
