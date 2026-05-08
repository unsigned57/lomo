package com.lomo.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

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
class NsdResolvedDeviceMapperTest {
    @Test
    fun `resolved ipv4 peer maps to discovered device`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Pixel",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.25")),
                port = 1080,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            )

        assertEquals("Pixel", device?.name)
        assertEquals("192.168.1.25", device?.host)
        assertEquals(1080, device?.port)
    }

    @Test
    fun `resolved ipv6 peer maps to bracketed http host when ipv4 is absent`() {
        val device =
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Tablet",
                hostAddresses = listOf(InetAddress.getByName("fd00::24")),
                port = 1081,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            )

        assertEquals("Tablet", device?.name)
        assertEquals("[fd00:0:0:0:0:0:0:24]", device?.host)
        assertEquals(1081, device?.port)
    }

    @Test
    fun `resolved peer prefers ipv4 when both address families are present`() {
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

        assertEquals("192.168.1.26", device?.host)
    }

    @Test
    fun `resolved self and incomplete endpoints are ignored`() {
        assertNull(
            mapResolvedLanShareDevice(
                serviceName = "Lomo-Local",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.27")),
                port = 1083,
                attributes = mapOf("uuid" to "local-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            ),
        )
        assertNull(
            mapResolvedLanShareDevice(
                serviceName = "Lomo-NoHost",
                hostAddresses = emptyList(),
                port = 1084,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            ),
        )
        assertNull(
            mapResolvedLanShareDevice(
                serviceName = "Lomo-NoPort",
                hostAddresses = listOf(InetAddress.getByName("192.168.1.28")),
                port = 0,
                attributes = mapOf("uuid" to "remote-uuid".toByteArray(Charsets.UTF_8)),
                localUuid = "local-uuid",
            ),
        )
    }
}
