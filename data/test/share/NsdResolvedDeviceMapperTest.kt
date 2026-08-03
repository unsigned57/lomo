/*
 * Behavior Contract:
 * - Unit under test: NSD resolved-device mapping for LAN share discovery.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: map protocol-v2 NSD records to device-key-identified peers independently of names.
 *
 * Scenarios:
 * - Given a peer with a valid device ID and reachable IPv4 or IPv6 endpoint, when it resolves, then
 *   its identity and numeric endpoint are retained.
 * - Given a peer with the same display name but another device ID, it remains discoverable.
 * - Given self, v1, malformed identity, or an incomplete endpoint, it is rejected at the edge.
 *
 * Observable outcomes:
 * - Mapped device ID, display name, host, port, and null results at invalid/self boundaries.
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

        test("resolved ipv6 peer keeps its numeric host when ipv4 is absent") { `resolved ipv6 peer keeps its numeric host when ipv4 is absent`() }

        test("resolved peer prefers ipv4 when both address families are present") { `resolved peer prefers ipv4 when both address families are present`() }

        test("same display name with different device id remains discoverable") { `same display name with different device id remains discoverable`() }

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
                attributes = v2Attributes(PEER_A_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            )

        device?.name shouldBe "Pixel"
        device?.deviceId shouldBe PEER_A_DEVICE_ID
        device?.host shouldBe "192.168.1.25"
        device?.port shouldBe 1080
    }

    private fun `resolved ipv6 peer keeps its numeric host when ipv4 is absent`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Tablet",
                hostAddresses = listOf(InetAddress.getByName("fd00::24")),
                port = 1081,
                attributes = v2Attributes(PEER_B_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            )

        device?.name shouldBe "Tablet"
        device?.host shouldBe "fd00:0:0:0:0:0:0:24"
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
                attributes = v2Attributes(PEER_C_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            )

        device?.host shouldBe "192.168.1.26"
    }

    private fun `same display name with different device id remains discoverable`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Pixel",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                port = 1083,
                attributes = v2Attributes(PEER_D_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            )

        device?.name shouldBe "Pixel"
        device?.deviceId shouldBe PEER_D_DEVICE_ID
    }

    private fun `resolved self invalid identity and incomplete endpoints are ignored`() {
        mapResolvedLanShareDevice(
                serviceName = "Lomo-Local",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                port = 1083,
                attributes = v2Attributes(LOCAL_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoUuid",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.29")),
                port = 1083,
                attributes = emptyMap(),
                localDeviceId = LOCAL_DEVICE_ID,
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-BadUuid",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.30")),
                port = 1083,
                attributes = v2Attributes("g".repeat(64)),
                localDeviceId = LOCAL_DEVICE_ID,
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoHost",
                hostAddresses = emptyList(),
                port = 1084,
                attributes = v2Attributes(PEER_E_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoPort",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.28")),
                port = 0,
                attributes = v2Attributes(PEER_F_DEVICE_ID),
                localDeviceId = LOCAL_DEVICE_ID,
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-V1",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.31")),
                port = 1083,
                attributes = mapOf("device_id" to PEER_F_DEVICE_ID.toByteArray(), "protocol_version" to "1".toByteArray()),
                localDeviceId = LOCAL_DEVICE_ID,
            ).shouldBeNull()
    }
}

private fun v2Attributes(deviceId: String): Map<String, ByteArray> =
    mapOf(
        "device_id" to deviceId.toByteArray(Charsets.UTF_8),
        "protocol_version" to "2".toByteArray(Charsets.UTF_8),
    )

private const val LOCAL_DEVICE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val PEER_A_DEVICE_ID = "1111111111111111111111111111111111111111111111111111111111111111"
private const val PEER_B_DEVICE_ID = "2222222222222222222222222222222222222222222222222222222222222222"
private const val PEER_C_DEVICE_ID = "3333333333333333333333333333333333333333333333333333333333333333"
private const val PEER_D_DEVICE_ID = "4444444444444444444444444444444444444444444444444444444444444444"
private const val PEER_E_DEVICE_ID = "5555555555555555555555555555555555555555555555555555555555555555"
private const val PEER_F_DEVICE_ID = "6666666666666666666666666666666666666666666666666666666666666666"
