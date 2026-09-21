/*
 * Behavior Contract:
 * - Unit under test: NSD resolved-device mapping for LAN share discovery.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: map protocol-v2 NSD records to device-key-identified peers independently of names.
 *
 * Scenarios:
 * - Given a peer with a valid device ID and reachable IPv4 or IPv6 endpoint at the active protocol
 *   version, when it resolves, then its identity and numeric endpoint are retained.
 * - Given a peer with the same display name but another device ID, it remains discoverable.
 * - Given self, a foreign protocol version, malformed identity, or an incomplete endpoint, it is
 *   rejected at the edge.
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
 * - Reason category: protocol owner moved to `lomo-lan` (T25).
 * - Old behavior/assertion being replaced: mapper accepted only hardcoded protocol_version "2".
 * - Why old assertion is no longer correct: the active LAN protocol is v3 AEAD control; Kotlin
 *   echoes the engine-owned version instead of choosing it.
 * - Coverage preserved by: valid records at the active version still map; foreign version "1" is
 *   still rejected.
 * - Why this is not fitting the test to the implementation: the product contract is "only the
 *   current protocol version is discoverable", not a frozen v2 string.
 */
package com.lomo.data.share

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.net.InetAddress

class NsdResolvedDeviceMapperTest : DataFunSpec() {
    init {
        test("resolved ipv4 peer maps to discovered device") {
            val device =
                mapResolvedLanShareDevice(
                    serviceName = "Lomo-Pixel",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.25")),
                    port = 1080,
                    attributes = v2Attributes(PEER_A_DEVICE_ID),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                )

            device?.name shouldBe "Pixel"
            device?.deviceId shouldBe PEER_A_DEVICE_ID
            device?.host shouldBe "192.168.1.25"
            device?.port shouldBe 1080
        }

        test("resolved ipv6 peer keeps its numeric host when ipv4 is absent") {
            val device =
                mapResolvedLanShareDevice(
                    serviceName = "Lomo-Tablet",
                    hostAddresses = listOf(InetAddress.getByName("fd00::24")),
                    port = 1081,
                    attributes = v2Attributes(PEER_B_DEVICE_ID),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                )

            device?.name shouldBe "Tablet"
            device?.host shouldBe "fd00:0:0:0:0:0:0:24"
            device?.port shouldBe 1081
        }

        test("resolved peer prefers ipv4 when both address families are present") {
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
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                )

            device?.host shouldBe "192.168.1.26"
        }

        test("same display name with different device id remains discoverable") {
            val device =
                mapResolvedLanShareDevice(
                    serviceName = "Lomo-Pixel",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                    port = 1083,
                    attributes = v2Attributes(PEER_D_DEVICE_ID),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                )

            device?.name shouldBe "Pixel"
            device?.deviceId shouldBe PEER_D_DEVICE_ID
        }

        test("resolved self invalid identity and incomplete endpoints are ignored") {
            mapResolvedLanShareDevice(
                    serviceName = "Lomo-Local",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                    port = 1083,
                    attributes = v2Attributes(LOCAL_DEVICE_ID),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                ).shouldBeNull()
            mapResolvedLanShareDevice(
                    serviceName = "Lomo-NoUuid",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.29")),
                    port = 1083,
                    attributes = emptyMap(),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                ).shouldBeNull()
            mapResolvedLanShareDevice(
                    serviceName = "Lomo-BadUuid",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.30")),
                    port = 1083,
                    attributes = v2Attributes("g".repeat(64)),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                ).shouldBeNull()
            mapResolvedLanShareDevice(
                    serviceName = "Lomo-NoHost",
                    hostAddresses = emptyList(),
                    port = 1084,
                    attributes = v2Attributes(PEER_E_DEVICE_ID),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                ).shouldBeNull()
            mapResolvedLanShareDevice(
                    serviceName = "Lomo-NoPort",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.28")),
                    port = 0,
                    attributes = v2Attributes(PEER_F_DEVICE_ID),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                ).shouldBeNull()
            mapResolvedLanShareDevice(
                    serviceName = "Lomo-V1",
                    hostAddresses = listOf(InetAddress.getByName("192.168.1.31")),
                    port = 1083,
                    attributes = mapOf("device_id" to PEER_F_DEVICE_ID.toByteArray(), "protocol_version" to "1".toByteArray()),
                    localDeviceId = LOCAL_DEVICE_ID,
                    expectedProtocolVersion = ACTIVE_PROTOCOL_VERSION,
                ).shouldBeNull()
        }
    }
}

private fun v2Attributes(deviceId: String): Map<String, ByteArray> =
    mapOf(
        "device_id" to deviceId.toByteArray(Charsets.UTF_8),
        "protocol_version" to ACTIVE_PROTOCOL_VERSION.toByteArray(Charsets.UTF_8),
    )

private const val ACTIVE_PROTOCOL_VERSION = "3"

private const val LOCAL_DEVICE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val PEER_A_DEVICE_ID = "1111111111111111111111111111111111111111111111111111111111111111"
private const val PEER_B_DEVICE_ID = "2222222222222222222222222222222222222222222222222222222222222222"
private const val PEER_C_DEVICE_ID = "3333333333333333333333333333333333333333333333333333333333333333"
private const val PEER_D_DEVICE_ID = "4444444444444444444444444444444444444444444444444444444444444444"
private const val PEER_E_DEVICE_ID = "5555555555555555555555555555555555555555555555555555555555555555"
private const val PEER_F_DEVICE_ID = "6666666666666666666666666666666666666666666666666666666666666666"
