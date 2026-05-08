package com.lomo.data.share

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: NsdDiscoveryService start/register request contract.
 * - Behavior focus: LAN discovery and advertising must report synchronous NSD request
 *   rejection and clean up asynchronous NSD listener failures so lifecycle state can reset and
 *   retry after local-network permission is granted; when lifecycle chooses a hotspot or other
 *   local network, NSD advertising and discovery must be scoped to that target network.
 * - Observable outcomes: Boolean return from discovery and registration request entrypoints,
 *   lifecycle failure callback count, no stale listener unregister call after async
 *   start/registration failure callbacks, and source-level adoption of target-network NSD
 *   registration/discovery calls.
 * - Red phase: Fails before the fix because startDiscovery/registerService return Unit after
 *   swallowing SecurityException, leaving callers unable to distinguish accepted from rejected
 *   NSD requests; the async cleanup companions fail before the fix because failure callbacks keep
 *   stale discovery/registration listeners installed; the network-scoped source contract fails
 *   before the current fix because both entrypoints only use process-wide NSD calls.
 * - Excludes: live mDNS traffic, Android permission dialog UI, and HTTP transfer behavior.
 */
class NsdDiscoveryServiceStartContractTest {
    private lateinit var context: Context
    private lateinit var nsdManager: NsdManager

    @Before
    fun setUp() {
        context = mockk()
        nsdManager = mockk()
        every { context.getSystemService(Context.NSD_SERVICE) } returns nsdManager
    }

    @After
    fun tearDown() {
        unmockkConstructor(NsdServiceInfo::class)
    }

    @Test
    fun `startDiscovery returns false when NSD rejects the request synchronously`() {
        every {
            nsdManager.discoverServices(
                NsdDiscoveryService.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                any<NsdManager.DiscoveryListener>(),
            )
        } throws SecurityException("missing local network permission")

        val service = NsdDiscoveryService(context)

        assertFalse(service.startDiscovery("local-uuid"))
    }

    @Test
    fun `startDiscovery returns true when NSD accepts the request`() {
        every {
            nsdManager.discoverServices(
                NsdDiscoveryService.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                any<NsdManager.DiscoveryListener>(),
            )
        } just Runs

        val service = NsdDiscoveryService(context)

        assertTrue(service.startDiscovery("local-uuid"))
    }

    @Test
    fun `startDiscovery clears discovery listener after async NSD start failure`() {
        val discoveryListener = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(
                NsdDiscoveryService.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                capture(discoveryListener),
            )
        } just Runs
        every { nsdManager.stopServiceDiscovery(any<NsdManager.DiscoveryListener>()) } just Runs

        var failureCount = 0
        val service = NsdDiscoveryService(context, onDiscoveryStartFailed = { failureCount += 1 })

        assertTrue(service.startDiscovery("local-uuid"))
        discoveryListener.captured.onStartDiscoveryFailed(NsdDiscoveryService.SERVICE_TYPE, 3)
        service.stopDiscovery()

        assertEquals(1, failureCount)
        verify(exactly = 0) {
            nsdManager.stopServiceDiscovery(any<NsdManager.DiscoveryListener>())
        }
    }

    @Test
    fun `registerService returns false when NSD rejects advertising synchronously`() {
        mockServiceInfoConstruction()
        every {
            nsdManager.registerService(
                any<NsdServiceInfo>(),
                NsdManager.PROTOCOL_DNS_SD,
                any<NsdManager.RegistrationListener>(),
            )
        } throws SecurityException("missing local network permission")

        val service = NsdDiscoveryService(context)

        assertFalse(service.registerService(port = 1080, deviceName = "Pixel", uuid = "local-uuid"))
    }

    @Test
    fun `registerService returns true when NSD accepts advertising`() {
        mockServiceInfoConstruction()
        every {
            nsdManager.registerService(
                any<NsdServiceInfo>(),
                NsdManager.PROTOCOL_DNS_SD,
                any<NsdManager.RegistrationListener>(),
            )
        } just Runs

        val service = NsdDiscoveryService(context)

        assertTrue(service.registerService(port = 1080, deviceName = "Pixel", uuid = "local-uuid"))
    }

    @Test
    fun `registerService clears registration listener after async NSD registration failure`() {
        mockServiceInfoConstruction()
        val registrationListener = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(
                any<NsdServiceInfo>(),
                NsdManager.PROTOCOL_DNS_SD,
                capture(registrationListener),
            )
        } just Runs
        every { nsdManager.unregisterService(any<NsdManager.RegistrationListener>()) } just Runs

        var failureCount = 0
        val service = NsdDiscoveryService(context, onServiceRegistrationFailed = { failureCount += 1 })

        assertTrue(service.registerService(port = 1080, deviceName = "Pixel", uuid = "local-uuid"))
        registrationListener.captured.onRegistrationFailed(mockk(), 4)
        service.unregisterService()

        assertEquals(1, failureCount)
        verify(exactly = 0) {
            nsdManager.unregisterService(any<NsdManager.RegistrationListener>())
        }
    }

    @Test
    fun `register and discovery requests are scoped to selected target network on android 13 plus`() {
        val source = File("src/main/java/com/lomo/data/share/NsdDiscoveryService.kt").readText()

        assertTrue(
            "NSD registration should accept a selected LAN Network from lifecycle.",
            source.contains("targetNetwork: Network?"),
        )
        assertTrue(
            "NsdServiceInfo should advertise on the selected LAN Network when platform supports it.",
            source.contains("setNetwork(targetNetwork)"),
        )
        assertTrue(
            "NSD discovery should accept the selected LAN Network from lifecycle.",
            source.contains("fun startDiscovery(\n        uuid: String,\n        targetNetwork: Network?"),
        )
        assertTrue(
            "Android 13+ discovery should use the network-scoped NsdManager overload.",
            source.contains("discoverServices(\n                    SERVICE_TYPE,\n                    NsdManager.PROTOCOL_DNS_SD,\n                    targetNetwork,"),
        )
    }

    private fun mockServiceInfoConstruction() {
        mockkConstructor(NsdServiceInfo::class)
        every { anyConstructed<NsdServiceInfo>().serviceName = any() } just Runs
        every { anyConstructed<NsdServiceInfo>().serviceType = any() } just Runs
        every { anyConstructed<NsdServiceInfo>().setPort(any()) } just Runs
        every { anyConstructed<NsdServiceInfo>().setAttribute(any(), any<String>()) } just Runs
    }
}
