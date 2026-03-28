package com.lomo.data.share

import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SharePairingConfig
 * - Behavior focus: pairing-code normalization/storage, device-name sanitization, and send gating based on E2E + pairing state.
 * - Observable outcomes: stored key material, exposed StateFlow pairing code, sanitized device name, and requiresPairingBeforeSend result.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Build.MODEL fallback lookup, HTTP client creation, and UI settings rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharePairingConfigTest {
    private val dataStore = mockk<LomoDataStore>(relaxed = true)

    @Test
    fun `setLanSharePairingCode normalizes input and stores derived key material`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            config.setLanSharePairingCode("  123 456  ")

            assertEquals("123 456", config.lanSharePairingCode.value)
            coVerify(exactly = 1) {
                dataStore.updateLanSharePairingKeyHex(
                    match { stored ->
                        stored.startsWith("v2:")
                    },
                )
            }
        }

    @Test
    fun `clearLanSharePairingCode clears store and in memory state`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)
            config.setLanSharePairingCode("654321")

            config.clearLanSharePairingCode()

            assertEquals("", config.lanSharePairingCode.value)
            coVerify(exactly = 1) { dataStore.updateLanSharePairingKeyHex(null) }
        }

    @Test
    fun `setLanShareDeviceName stores sanitized device name`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            config.setLanShareDeviceName("  My\u0000  Phone\t ")

            coVerify(exactly = 1) { dataStore.updateLanShareDeviceName("My Phone") }
        }

    @Test
    fun `requiresPairingBeforeSend returns false when e2e is disabled`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(false)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            val requiresPairing = config.requiresPairingBeforeSend()

            assertEquals(false, requiresPairing)
        }

    @Test
    fun `requiresPairingBeforeSend returns true when e2e is enabled without valid key`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf("not-a-valid-key")
            every { dataStore.lanShareDeviceName } returns flowOf("  My\u0000  Phone\t ")
            val config = SharePairingConfig(dataStore)

            val requiresPairing = config.requiresPairingBeforeSend()
            val resolvedName = config.resolveDeviceName()

            assertTrue(requiresPairing)
            assertEquals("My Phone", resolvedName)
        }
}
