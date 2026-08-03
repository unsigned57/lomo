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
 *
 * Test Change Justification:
 * - Reason category: domain identity and product-port contract change.
 * - Old behavior/assertion being replaced: UUID-shaped fixture values and the deleted LAN v1 discovery-port constant.
 * - Why old assertion is no longer correct: discovered peers now carry the LAN v2 cryptographic deviceId and the test must not depend on a deleted wire constant.
 * - Coverage preserved by: lost-service endpoint removal and stale-endpoint replacement assertions are unchanged.
 * - Why this is not fitting the test to the implementation: fixture data now satisfies the public LAN v2 boundary while observable registry behavior remains identical.
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
    deviceId: String,
    host: String,
): DiscoveredDevice =
    DiscoveredDevice(
        deviceId = deviceId,
        name = "Pixel",
        host = host,
        port = TEST_PORT,
    )

private const val SERVICE_A_KEY = "Lomo-Pixel|_lomo-share._tcp."
private const val SERVICE_B_KEY = "Lomo-Pixel (2)|_lomo-share._tcp."
private const val PEER_A_UUID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val PEER_B_UUID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val PEER_C_UUID = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
private const val TEST_PORT = 53317
