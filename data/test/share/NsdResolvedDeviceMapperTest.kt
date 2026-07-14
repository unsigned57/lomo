/*
 * Behavior Contract:
 * - Unit under test: NSD resolved-device mapping for LAN share discovery.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: map resolved NSD records to UUID-identified peers independently of display names.
 *
 * Scenarios:
 * - Given a peer with a valid UUID and reachable IPv4 or IPv6 endpoint, when it resolves, then its
 *   UUID and HTTP-ready endpoint are retained.
 * - Given a peer with the same display name but a different UUID, when it resolves, then it remains discoverable.
 * - Given the local UUID, a missing/invalid UUID, or an incomplete endpoint, when it resolves, then it is rejected.
 *
 * Observable outcomes:
 * - Mapped UUID, display name, host, port, and null results at invalid/self boundaries.
 *
 * TDD proof:
 * - RED: before the fix, the mapper dropped the UUID from DiscoveredDevice and accepted records without a valid UUID.
 *
 * Excludes:
 * - Live mDNS traffic, Android NsdManager callback delivery, and Ktor transfer calls.
 *
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: asserting mapper mappings with simple placeholders like "remote-uuid".
 * - Why old assertion is no longer correct: NSD discovery now enforces valid UUID formats for deduplication and pings.
 * - Coverage preserved by: asserting that valid UUIDs are mapped correctly, and invalid UUID formats are ignored/rejected.
 * - Why this is not fitting the test to the implementation: it ensures that peer identity validation conforms to the new ping/identity protocol.
 */
package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.net.InetAddress

class NsdResolvedDeviceMapperTest : DataFunSpec() {
    init {
        test("resolved ipv4 peer maps to discovered device") { `resolved ipv4 peer maps to discovered device`() }

        test("resolved ipv6 peer maps to bracketed http host when ipv4 is absent") { `resolved ipv6 peer maps to bracketed http host when ipv4 is absent`() }

        test("resolved peer prefers ipv4 when both address families are present") { `resolved peer prefers ipv4 when both address families are present`() }

        test("same display name with different uuid remains discoverable") { `same display name with different uuid remains discoverable`() }

        test("resolved self invalid identity and incomplete endpoints are ignored") {
            `resolved self invalid identity and incomplete endpoints are ignored`()
        }
    }


    private fun `resolved ipv4 peer maps to discovered device`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Pixel",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.25")),
                port = 1080,
                attributes = mapOf("uuid" to "11111111-1111-1111-1111-111111111111".toByteArray(Charsets.UTF_8)),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            )

        device?.name shouldBe "Pixel"
        device?.uuid shouldBe "11111111-1111-1111-1111-111111111111"
        device?.host shouldBe "192.168.1.25"
        device?.port shouldBe 1080
    }

    private fun `resolved ipv6 peer maps to bracketed http host when ipv4 is absent`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Tablet",
                hostAddresses = listOf(InetAddress.getByName("fd00::24")),
                port = 1081,
                attributes = mapOf("uuid" to "22222222-2222-2222-2222-222222222222".toByteArray(Charsets.UTF_8)),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            )

        device?.name shouldBe "Tablet"
        device?.host shouldBe "[fd00:0:0:0:0:0:0:24]"
        device?.port shouldBe 1081
    }

    private fun `resolved peer prefers ipv4 when both address families are present`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Phone",
                hostAddresses =
                    listOf(
                        InetAddress.getByName("fd00::25"),
                        InetAddress.getByName("192.168.1.26"),
                    ),
                port = 1082,
                attributes = mapOf("uuid" to "33333333-3333-3333-3333-333333333333".toByteArray(Charsets.UTF_8)),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            )

        device?.host shouldBe "192.168.1.26"
    }

    private fun `same display name with different uuid remains discoverable`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Pixel",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                port = 1083,
                attributes = mapOf("uuid" to "44444444-4444-4444-4444-444444444444".toByteArray()),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            )

        device?.name shouldBe "Pixel"
        device?.uuid shouldBe "44444444-4444-4444-4444-444444444444"
    }

    private fun `resolved self invalid identity and incomplete endpoints are ignored`() {
        mapResolvedLanShareDevice(
                serviceName = "Lomo-Local",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                port = 1083,
                attributes = mapOf("uuid" to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa".toByteArray(Charsets.UTF_8)),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoUuid",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.29")),
                port = 1083,
                attributes = emptyMap(),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-BadUuid",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.30")),
                port = 1083,
                attributes = mapOf("uuid" to "not-a-uuid".toByteArray()),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoHost",
                hostAddresses = emptyList(),
                port = 1084,
                attributes = mapOf("uuid" to "55555555-5555-5555-5555-555555555555".toByteArray(Charsets.UTF_8)),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoPort",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.28")),
                port = 0,
                attributes = mapOf("uuid" to "66666666-6666-6666-6666-666666666666".toByteArray(Charsets.UTF_8)),
                localUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ).shouldBeNull()
    }
}
