/*
 * Behavior Contract:
 * - Unit under test: LAN share discovered-device merge policy.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: merge NSD results by stable device identity.
 *
 * Scenarios:
 * - Given the same device ID at a changed endpoint, when a fresh result arrives, then it replaces the stale endpoint.
 * - Given two peers with the same display name but different IDs, when results merge, then both remain visible.
 *
 * Observable outcomes:
 * - The merged DiscoveredDevice list and retained UUID/endpoint values.
 *
 * TDD proof:
 * - RED: before the fix, the merge policy keyed only by endpoint, so a peer moving endpoints was duplicated by UUID.
 *
 * Excludes:
 * - NSD callback timing, HTTP probing, and UI ordering.
 *
 * Test Change Justification:
 * - Reason category: domain identity contract change.
 * - Old behavior/assertion being replaced: nullable UUID allowed endpoint-only fallback when peer identity was absent.
 * - Why old assertion is no longer correct: LAN v2 requires a non-empty cryptographic deviceId, so identity-less peers are rejected at the boundary and cannot reach merge policy.
 * - Coverage preserved by: endpoint replacement for one stable peer and same-name separation for distinct peers remain asserted.
 * - Why this is not fitting the test to the implementation: the removed case represented a state now made impossible by the public DiscoveredDevice type.
 */
package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DiscoveredDevice
import io.kotest.matchers.shouldBe

class LanShareDiscoveredDeviceDedupeTest : DataFunSpec() {
    init {
        test("same device id at a fresh endpoint replaces the stale endpoint") {
            val stale = device(deviceId = PEER_A_DEVICE_ID, name = "Pixel", host = "192.168.1.20")
            val fresh = device(deviceId = PEER_A_DEVICE_ID, name = "Pixel", host = "192.168.1.21")

            mergeLanShareDiscoveredDevices(existing = listOf(stale), incoming = listOf(fresh)) shouldBe listOf(fresh)
        }

        test("same display name with different device ids keeps both peers") {
            val first = device(deviceId = PEER_A_DEVICE_ID, name = "Pixel", host = "192.168.1.20")
            val second = device(deviceId = PEER_B_DEVICE_ID, name = "Pixel", host = "192.168.1.21")

            mergeLanShareDiscoveredDevices(existing = listOf(first), incoming = listOf(second)) shouldBe
                listOf(first, second)
        }
    }
}

private fun device(
    deviceId: String,
    name: String,
    host: String,
): DiscoveredDevice =
    DiscoveredDevice(
        deviceId = deviceId,
        name = name,
        host = host,
        port = TEST_PORT,
    )

private const val PEER_A_DEVICE_ID = "1111111111111111111111111111111111111111111111111111111111111111"
private const val PEER_B_DEVICE_ID = "2222222222222222222222222222222222222222222222222222222222222222"
private const val TEST_PORT = 53317
