/*
 * Test Contract:
 * - Unit under test: LanShareNetworkPermissionPolicyTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for LanShareNetworkPermissionPolicyTest.
 * - Boundary: boundary and edge cases for LanShareNetworkPermissionPolicyTest.
 * - Failure: failure and error scenarios for LanShareNetworkPermissionPolicyTest.
 * - Must-not-happen: invariants are never violated for LanShareNetworkPermissionPolicyTest.
 *
 * - Behavior focus: test behavioral outcomes of LanShareNetworkPermissionPolicyTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.share

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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

        test("API 33 (Tiramisu) requires NEARBY_WIFI_DEVICES only") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_TIRAMISU,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldContain NEARBY_WIFI_DEVICES_PERMISSION
            permissions shouldNotContain ACCESS_LOCAL_NETWORK_PERMISSION
        }

        test("API 34 (UpsideDownCake) still requires NEARBY_WIFI_DEVICES only") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_UPSIDE_DOWN_CAKE,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldContain NEARBY_WIFI_DEVICES_PERMISSION
            permissions shouldNotContain ACCESS_LOCAL_NETWORK_PERMISSION
        }

        test("API 35 (VanillaIceCream) still requires NEARBY_WIFI_DEVICES only") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_VANILLA_ICE_CREAM,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldContain NEARBY_WIFI_DEVICES_PERMISSION
            permissions shouldNotContain ACCESS_LOCAL_NETWORK_PERMISSION
        }

        test("API 36 (Baklava) requests ACCESS_LOCAL_NETWORK only when system recognizes the permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldContain NEARBY_WIFI_DEVICES_PERMISSION
            permissions shouldContain ACCESS_LOCAL_NETWORK_PERMISSION
        }

        test("API 36 skips ACCESS_LOCAL_NETWORK when system does not recognize the permission") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA,
                    isPermissionRecognized = PermissionNeverRecognized,
                )

            permissions shouldContain NEARBY_WIFI_DEVICES_PERMISSION
            permissions shouldNotContain ACCESS_LOCAL_NETWORK_PERMISSION
        }

        test("API 37+ still gates ACCESS_LOCAL_NETWORK on system recognition") {
            val permissions =
                requiredLanShareNetworkPermissions(
                    sdkInt = SDK_BAKLAVA_PLUS_ONE,
                    isPermissionRecognized = PermissionAlwaysRecognized,
                )

            permissions shouldContain NEARBY_WIFI_DEVICES_PERMISSION
            permissions shouldContain ACCESS_LOCAL_NETWORK_PERMISSION
        }

        test("permission request is granted when all required permissions return true") {
            val required = listOf(NEARBY_WIFI_DEVICES_PERMISSION, ACCESS_LOCAL_NETWORK_PERMISSION)

            val granted = isLanSharePermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(
                    NEARBY_WIFI_DEVICES_PERMISSION to true,
                    ACCESS_LOCAL_NETWORK_PERMISSION to true,
                ),
                hasCurrentPermissions = false,
            )

            granted shouldBe true
        }

        test("permission request falls back to current grant state when callback misses entries") {
            val required = listOf(NEARBY_WIFI_DEVICES_PERMISSION, ACCESS_LOCAL_NETWORK_PERMISSION)

            val granted = isLanSharePermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(NEARBY_WIFI_DEVICES_PERMISSION to true),
                hasCurrentPermissions = true,
            )

            granted shouldBe true
        }

        test("permission request fails when a required permission is denied and OS doesn't already grant it") {
            val required = listOf(NEARBY_WIFI_DEVICES_PERMISSION, ACCESS_LOCAL_NETWORK_PERMISSION)

            val granted = isLanSharePermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(
                    NEARBY_WIFI_DEVICES_PERMISSION to true,
                    ACCESS_LOCAL_NETWORK_PERMISSION to false,
                ),
                hasCurrentPermissions = false,
            )

            granted shouldBe false
        }
    }
}
