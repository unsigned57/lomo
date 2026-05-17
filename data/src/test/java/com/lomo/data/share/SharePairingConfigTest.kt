package com.lomo.data.share


import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: SharePairingConfig
 * - Behavior focus: pairing-code normalization/storage, device-name sanitization, and send gating based on E2E + pairing state.
 * - Observable outcomes: stored key material, exposed StateFlow pairing code, sanitized device name, and requiresPairingBeforeSend result.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: Build.MODEL fallback lookup, HTTP client creation, and UI settings rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharePairingConfigTest : DataFunSpec() {
    init {
        test("setLanSharePairingCode normalizes input and stores derived key material") { `setLanSharePairingCode normalizes input and stores derived key material`() }

        test("clearLanSharePairingCode clears store and in memory state") { `clearLanSharePairingCode clears store and in memory state`() }

        test("setLanShareDeviceName stores sanitized device name") { `setLanShareDeviceName stores sanitized device name`() }

        test("setLanShareDeviceName strips bidi spoofing controls") { `setLanShareDeviceName strips bidi spoofing controls`() }

        test("requiresPairingBeforeSend returns false when e2e is disabled") { `requiresPairingBeforeSend returns false when e2e is disabled`() }

        test("requiresPairingBeforeSend returns true when e2e is enabled without valid key") { `requiresPairingBeforeSend returns true when e2e is enabled without valid key`() }

        test("setLanShareEnabled delegates to datastore") { `setLanShareEnabled delegates to datastore`() }

        test("isLanShareEnabled reflects datastore flag") { `isLanShareEnabled reflects datastore flag`() }
    }


    private val dataStore = mockk<LomoDataStore>(relaxed = true)

    private fun `setLanSharePairingCode normalizes input and stores derived key material`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            config.setLanSharePairingCode("  123 456  ")

            config.lanSharePairingCode.value shouldBe "123 456"
            coVerify(exactly = 1) {
                dataStore.updateLanSharePairingKeyHex(
                    match { stored ->
                        stored.startsWith("v2:")
                    },
                )
            }
        }

    private fun `clearLanSharePairingCode clears store and in memory state`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)
            config.setLanSharePairingCode("654321")

            config.clearLanSharePairingCode()

            config.lanSharePairingCode.value shouldBe ""
            coVerify(exactly = 1) { dataStore.updateLanSharePairingKeyHex(null) }
        }

    private fun `setLanShareDeviceName stores sanitized device name`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            config.setLanShareDeviceName("  My\u0000  Phone\t ")

            coVerify(exactly = 1) { dataStore.updateLanShareDeviceName("My Phone") }
        }

    private fun `setLanShareDeviceName strips bidi spoofing controls`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            config.setLanShareDeviceName("  My\u202E Phone\u2066  ")

            coVerify(exactly = 1) { dataStore.updateLanShareDeviceName("My Phone") }
        }

    private fun `requiresPairingBeforeSend returns false when e2e is disabled`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(false)
            every { dataStore.lanSharePairingKeyHex } returns flowOf(null)
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore)

            val requiresPairing = config.requiresPairingBeforeSend()

            requiresPairing shouldBe false
        }

    private fun `requiresPairingBeforeSend returns true when e2e is enabled without valid key`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            every { dataStore.lanSharePairingKeyHex } returns flowOf("not-a-valid-key")
            every { dataStore.lanShareDeviceName } returns flowOf("  My\u0000  Phone\t ")
            val config = SharePairingConfig(dataStore)

            val requiresPairing = config.requiresPairingBeforeSend()
            val resolvedName = config.resolveDeviceName()

            (requiresPairing).shouldBeTrue()
            resolvedName shouldBe "My Phone"
        }

    private fun `setLanShareEnabled delegates to datastore`() =
        runTest {
            val config = SharePairingConfig(dataStore)

            config.setLanShareEnabled(false)

            coVerify(exactly = 1) { dataStore.updateLanShareEnabled(false) }
        }

    private fun `isLanShareEnabled reflects datastore flag`() =
        runTest {
            every { dataStore.lanShareEnabled } returns flowOf(false)
            val config = SharePairingConfig(dataStore)

            val enabled = config.isLanShareEnabled()

            enabled shouldBe false
        }
}
