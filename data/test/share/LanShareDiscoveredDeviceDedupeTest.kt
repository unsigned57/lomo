/*
 * Behavior Contract:
 * - Unit under test: LAN share discovered-device merge policy.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: merge NSD and active-probe results by stable device identity.
 *
 * Scenarios:
 * - Given the same UUID at a changed endpoint, when a fresh result arrives, then it replaces the stale endpoint.
 * - Given two peers with the same display name but different UUIDs, when results merge, then both remain visible.
 * - Given identity is unavailable, when the same endpoint arrives, then endpoint fallback replaces the stale entry.
 *
 * Observable outcomes:
 * - The merged DiscoveredDevice list and retained UUID/endpoint values.
 *
 * TDD proof:
 * - RED: before the fix, the merge policy keyed only by endpoint, so a peer moving endpoints was duplicated by UUID.
 *
 * Excludes:
 * - NSD callback timing, HTTP probing, and UI ordering.
 */
package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DiscoveredDevice
import io.kotest.matchers.shouldBe

class LanShareDiscoveredDeviceDedupeTest : DataFunSpec() {
    init {
        test("same uuid at a fresh endpoint replaces the stale endpoint") {
            val stale = device(uuid = PEER_A_UUID, name = "Pixel", host = "192.168.1.20")
            val fresh = device(uuid = PEER_A_UUID, name = "Pixel", host = "192.168.1.21")

            mergeLanShareDiscoveredDevices(existing = listOf(stale), incoming = listOf(fresh)) shouldBe listOf(fresh)
        }

        test("same display name with different uuids keeps both peers") {
            val first = device(uuid = PEER_A_UUID, name = "Pixel", host = "192.168.1.20")
            val second = device(uuid = PEER_B_UUID, name = "Pixel", host = "192.168.1.21")

            mergeLanShareDiscoveredDevices(existing = listOf(first), incoming = listOf(second)) shouldBe
                listOf(first, second)
        }

        test("missing uuid falls back to endpoint replacement") {
            val stale = device(uuid = null, name = "Unknown", host = "192.168.1.20")
            val fresh = device(uuid = null, name = "Renamed", host = "192.168.1.20")

            mergeLanShareDiscoveredDevices(existing = listOf(stale), incoming = listOf(fresh)) shouldBe listOf(fresh)
        }
    }
}

private fun device(
    uuid: String?,
    name: String,
    host: String,
): DiscoveredDevice =
    DiscoveredDevice(
        uuid = uuid,
        name = name,
        host = host,
        port = LAN_SHARE_DISCOVERY_PORT,
    )

private const val PEER_A_UUID = "11111111-1111-1111-1111-111111111111"
private const val PEER_B_UUID = "22222222-2222-2222-2222-222222222222"
