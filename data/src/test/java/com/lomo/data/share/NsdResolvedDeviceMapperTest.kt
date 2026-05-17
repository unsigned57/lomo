package com.lomo.data.share


import java.net.InetAddress
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: NSD resolved-device mapping for LAN share discovery.
 * - Behavior focus: resolved NSD peers must become reachable DiscoveredDevice entries on IPv4 and
 *   IPv6 networks, while self advertisements and incomplete endpoints must be ignored.
 * - Observable outcomes: mapped device name, HTTP-ready host string, port, and null results for
 *   self or invalid service records.
 * - Red phase: Fails before the fix because NsdDiscoveryService only accepts IPv4 hosts inline
 *   and has no IPv6-safe endpoint mapping contract.
 * - Excludes: live mDNS traffic, Android NsdManager callback delivery, and Ktor transfer calls.
 */
class NsdResolvedDeviceMapperTest : DataFunSpec() {
    init {
        test("resolved ipv4 peer maps to discovered device") { `resolved ipv4 peer maps to discovered device`() }

        test("resolved ipv6 peer maps to bracketed http host when ipv4 is absent") { `resolved ipv6 peer maps to bracketed http host when ipv4 is absent`() }

        test("resolved peer prefers ipv4 when both address families are present") { `resolved peer prefers ipv4 when both address families are present`() }

        test("resolved self and incomplete endpoints are ignored") { `resolved self and incomplete endpoints are ignored`() }
    }


    private fun `resolved ipv4 peer maps to discovered device`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Pixel",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.25")),
                port = 1080,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            )

        device?.name shouldBe "Pixel"
        device?.host shouldBe "192.168.1.25"
        device?.port shouldBe 1080
    }

    private fun `resolved ipv6 peer maps to bracketed http host when ipv4 is absent`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Tablet",
                hostAddresses = listOf(InetAddress.getByName("fd00::24")),
                port = 1081,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
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
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            )

        device?.host shouldBe "192.168.1.26"
    }

    private fun `resolved self and incomplete endpoints are ignored`() {
        mapResolvedLanShareDevice(
                serviceName = "Lomo-Local",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                port = 1083,
                attributes = mapOf("uuid" to "local-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoHost",
                hostAddresses = emptyList(),
                port = 1084,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            ).shouldBeNull()
        mapResolvedLanShareDevice(
                serviceName = "Lomo-NoPort",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.28")),
                port = 0,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            ).shouldBeNull()
    }
}
