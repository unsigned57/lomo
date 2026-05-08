package com.lomo.app.feature.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: LAN share local-network permission policy.
 * - Behavior focus: discovery on Android releases that enforce local-network access must request
 *   the runtime permissions that exist for that platform release, recover from stale permission
 *   launcher results, and declare the manifest permission entries before NSD discovery or
 *   advertising.
 * - Observable outcomes: required permission list by SDK level, permission request-result
 *   classification, and manifest permission entry.
 * - Red phase: Fails before the fix because the runtime permission policy asks API 36 for
 *   ACCESS_LOCAL_NETWORK even though Android 16 LAN discovery is still granted by
 *   NEARBY_WIFI_DEVICES, so a device with Nearby Devices allowed is still classified as missing
 *   permission.
 * - Excludes: Android system permission dialog UI, NSD packet delivery, and transfer transport.
 */
/*
 * Test Change Justification:
 * - Reason category: platform contract correction.
 * - Old behavior/assertion being replaced: SDK 36 was treated as requiring both
 *   NEARBY_WIFI_DEVICES and ACCESS_LOCAL_NETWORK.
 * - Why old assertion is no longer correct: Android 16 local-network discovery is still covered
 *   by NEARBY_WIFI_DEVICES; ACCESS_LOCAL_NETWORK is the Android 17+ runtime gate.
 * - Coverage preserved by: below-33 still asserts no extra permission, API 33-36 lock Nearby
 *   Devices, and API 37+ still locks the future local-network permission.
 * - Why this is not fitting the test to the implementation: the assertion follows the platform
 *   release matrix instead of the current implementation.
 */
class LanShareNetworkPermissionPolicyTest {
    @Test
    fun `sdk 37 and above requires nearby wifi and local network permissions for lan share discovery`() {
        assertEquals(
            listOf(NEARBY_WIFI_DEVICES_PERMISSION, ACCESS_LOCAL_NETWORK_PERMISSION),
            requiredLanShareNetworkPermissions(sdkInt = 37).toList(),
        )
    }

    @Test
    fun `sdk 36 requires nearby wifi permission without local network permission for android 16`() {
        assertEquals(
            listOf(NEARBY_WIFI_DEVICES_PERMISSION),
            requiredLanShareNetworkPermissions(sdkInt = 36).toList(),
        )
    }

    @Test
    fun `sdk 36 nearby wifi grant satisfies lan share permission request`() {
        val requiredPermissions = requiredLanShareNetworkPermissions(sdkInt = 36)

        assertTrue(
            isLanSharePermissionRequestGranted(
                requiredPermissions = requiredPermissions,
                permissionResults = mapOf(NEARBY_WIFI_DEVICES_PERMISSION to true),
                hasCurrentPermissions = false,
            ),
        )
    }

    @Test
    fun `sdk 33 through 35 requires nearby wifi permission for lan share discovery`() {
        assertEquals(
            listOf(NEARBY_WIFI_DEVICES_PERMISSION),
            requiredLanShareNetworkPermissions(sdkInt = 33).toList(),
        )
        assertEquals(
            listOf(NEARBY_WIFI_DEVICES_PERMISSION),
            requiredLanShareNetworkPermissions(sdkInt = 34).toList(),
        )
        assertEquals(
            listOf(NEARBY_WIFI_DEVICES_PERMISSION),
            requiredLanShareNetworkPermissions(sdkInt = 35).toList(),
        )
    }

    @Test
    fun `sdk below android 13 does not request extra lan share permission`() {
        assertTrue(requiredLanShareNetworkPermissions(sdkInt = 32).isEmpty())
    }

    @Test
    fun `permission request result is granted when current permission state is granted despite stale result map`() {
        val requiredPermissions =
            listOf(NEARBY_WIFI_DEVICES_PERMISSION, ACCESS_LOCAL_NETWORK_PERMISSION)
        val staleLauncherResult =
            mapOf(
                NEARBY_WIFI_DEVICES_PERMISSION to true,
                ACCESS_LOCAL_NETWORK_PERMISSION to false,
            )

        assertTrue(
            isLanSharePermissionRequestGranted(
                requiredPermissions = requiredPermissions,
                permissionResults = staleLauncherResult,
                hasCurrentPermissions = true,
            ),
        )
    }

    @Test
    fun `permission request result stays denied when launcher and current state are denied`() {
        val requiredPermissions =
            listOf(NEARBY_WIFI_DEVICES_PERMISSION, ACCESS_LOCAL_NETWORK_PERMISSION)
        val deniedLauncherResult =
            mapOf(
                NEARBY_WIFI_DEVICES_PERMISSION to true,
                ACCESS_LOCAL_NETWORK_PERMISSION to false,
            )

        assertFalse(
            isLanSharePermissionRequestGranted(
                requiredPermissions = requiredPermissions,
                permissionResults = deniedLauncherResult,
                hasCurrentPermissions = false,
            ),
        )
    }

    @Test
    fun `app manifest declares local network permission for nsd discovery`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            "LAN share NSD must declare android.permission.ACCESS_LOCAL_NETWORK.",
            manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"),
        )
    }

    @Test
    fun `app manifest declares nearby wifi devices permission for android 16 nsd discovery`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            "LAN share NSD must declare android.permission.NEARBY_WIFI_DEVICES for Android 16.",
            manifest.contains("android.permission.NEARBY_WIFI_DEVICES"),
        )
    }
}
