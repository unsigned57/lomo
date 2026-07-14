/*
 * Behavior Contract:
 * - Unit under test: NSD service-to-endpoint registry.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: remove only the endpoint belonging to a lost NSD service instance.
 *
 * Scenarios:
 * - Given same-named peers resolved under different service keys, when one service is lost, then only its endpoint is removed.
 * - Given a service resolves to a changed endpoint, when the mapping is updated, then the stale endpoint is returned for deletion.
 *
 * Observable outcomes:
 * - Returned endpoint keys and the remaining discovered-device list.
 *
 * TDD proof:
 * - RED: before the fix, production had no service-to-endpoint registry and deleted every device sharing the lost display name.
 *
 * Excludes:
 * - Android NsdManager delivery and ServiceInfoCallback lifecycle.
 */
package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DiscoveredDevice
import io.kotest.matchers.shouldBe

class LanShareNsdEndpointRegistryTest : DataFunSpec() {
    init {
        test("lost service removes only its resolved endpoint even when display names match") {
            val registry = LanShareNsdEndpointRegistry()
            val first = nsdDevice(PEER_A_UUID, "192.168.1.20")
            val second = nsdDevice(PEER_B_UUID, "192.168.1.21")
            val activeSameName = nsdDevice(PEER_C_UUID, "192.168.1.22")
            registry.record(SERVICE_A_KEY, first)
            registry.record(SERVICE_B_KEY, second)

            val lostEndpoint = registry.remove(SERVICE_A_KEY)
            val remaining = removeLanShareEndpoint(listOf(first, second, activeSameName), lostEndpoint)

            remaining shouldBe listOf(second, activeSameName)
        }

        test("updated service mapping returns the stale endpoint") {
            val registry = LanShareNsdEndpointRegistry()
            val stale = nsdDevice(PEER_A_UUID, "192.168.1.20")
            val fresh = nsdDevice(PEER_A_UUID, "192.168.1.30")
            registry.record(SERVICE_A_KEY, stale) shouldBe null

            registry.record(SERVICE_A_KEY, fresh) shouldBe stale.lanShareEndpointKey()
            registry.remove(SERVICE_A_KEY) shouldBe fresh.lanShareEndpointKey()
        }
    }
}

private fun nsdDevice(
    uuid: String,
    host: String,
): DiscoveredDevice =
    DiscoveredDevice(
        uuid = uuid,
        name = "Pixel",
        host = host,
        port = LAN_SHARE_DISCOVERY_PORT,
    )

private const val SERVICE_A_KEY = "Lomo-Pixel|_lomo-share._tcp."
private const val SERVICE_B_KEY = "Lomo-Pixel (2)|_lomo-share._tcp."
private const val PEER_A_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
private const val PEER_B_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
private const val PEER_C_UUID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
