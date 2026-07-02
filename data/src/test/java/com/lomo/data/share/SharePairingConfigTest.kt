package com.lomo.data.share

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadException
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: SharePairingConfig
 * - Behavior focus: pairing-code normalization/storage, device-name sanitization, and send gating based on E2E + pairing state.
 * - Observable outcomes: stored key material, exposed StateFlow pairing code, sanitized device name, and requiresPairingBeforeSend result.
 * - TDD proof: Fails before behavior changes or migration are applied.
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

        test("given credential reads are locked when effective pairing key is requested then locked status is surfaced") {
            `given credential reads are locked when effective pairing key is requested then locked status is surfaced`()
        }

        test("setLanShareEnabled delegates to datastore") { `setLanShareEnabled delegates to datastore`() }

        test("isLanShareEnabled reflects datastore flag") { `isLanShareEnabled reflects datastore flag`() }
    }


    private val dataStore = mockk<LomoDataStore>(relaxed = true)

    private fun `setLanSharePairingCode normalizes input and stores derived key material`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val credentialRepository = FakeCredentialRepository()
            val config = SharePairingConfig(dataStore, credentialRepository, AuthorizedSecuritySessionPolicy)

            config.setLanSharePairingCode("  123 456  ")

            config.lanSharePairingCode.value shouldBe "123 456"
            credentialRepository.lanPairingKeyHex?.startsWith("v2:") shouldBe true
        }

    private fun `clearLanSharePairingCode clears store and in memory state`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val credentialRepository = FakeCredentialRepository()
            val config = SharePairingConfig(dataStore, credentialRepository, AuthorizedSecuritySessionPolicy)
            config.setLanSharePairingCode("654321")

            config.clearLanSharePairingCode()

            config.lanSharePairingCode.value shouldBe ""
            credentialRepository.lanPairingKeyHex shouldBe null
        }

    private fun `setLanShareDeviceName stores sanitized device name`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore, FakeCredentialRepository(), AuthorizedSecuritySessionPolicy)

            config.setLanShareDeviceName("  My\u0000  Phone\t ")

            coVerify(exactly = 1) { dataStore.updateLanShareDeviceName("My Phone") }
        }

    private fun `setLanShareDeviceName strips bidi spoofing controls`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore, FakeCredentialRepository(), AuthorizedSecuritySessionPolicy)

            config.setLanShareDeviceName("  My\u202E Phone\u2066  ")

            coVerify(exactly = 1) { dataStore.updateLanShareDeviceName("My Phone") }
        }

    private fun `requiresPairingBeforeSend returns false when e2e is disabled`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(false)
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            every { dataStore.lanShareDeviceName } returns flowOf("My Device")
            val config = SharePairingConfig(dataStore, FakeCredentialRepository(), AuthorizedSecuritySessionPolicy)

            val requiresPairing = config.requiresPairingBeforeSend()

            requiresPairing shouldBe false
        }

    private fun `requiresPairingBeforeSend returns true when e2e is enabled without valid key`() =
        runTest {
            every { dataStore.lanShareE2eEnabled } returns flowOf(true)
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            every { dataStore.lanShareDeviceName } returns flowOf("  My\u0000  Phone\t ")
            val config =
                SharePairingConfig(
                    dataStore,
                    FakeCredentialRepository(lanPairingKeyHex = "not-a-valid-key"),
                    AuthorizedSecuritySessionPolicy,
                )

            val requiresPairing = config.requiresPairingBeforeSend()
            val resolvedName = config.resolveDeviceName()

            (requiresPairing).shouldBeTrue()
            resolvedName shouldBe "My Phone"
        }

    private fun `given credential reads are locked when effective pairing key is requested then locked status is surfaced`() =
        runTest {
            coEvery { dataStore.drainLegacyLanSharePairingKeyHex() } returns null
            val config =
                SharePairingConfig(
                    dataStore,
                    FakeCredentialRepository(lanPairingKeyHex = "v2:abcdef"),
                    LockedSecuritySessionPolicy,
                )

            val failure = shouldThrow<CredentialSecretReadException> {
                config.getEffectivePairingKeyHex()
            }

            failure.result shouldBe
                CredentialSecretReadResult.Unauthorized(CredentialReadDenialReason.SecuritySessionLocked)
        }

    private fun `setLanShareEnabled delegates to datastore`() =
        runTest {
            val config = SharePairingConfig(dataStore, FakeCredentialRepository(), AuthorizedSecuritySessionPolicy)

            config.setLanShareEnabled(false)

            coVerify(exactly = 1) { dataStore.updateLanShareEnabled(false) }
        }

    private fun `isLanShareEnabled reflects datastore flag`() =
        runTest {
            every { dataStore.lanShareEnabled } returns flowOf(false)
            val config = SharePairingConfig(dataStore, FakeCredentialRepository(), AuthorizedSecuritySessionPolicy)

            val enabled = config.isLanShareEnabled()

            enabled shouldBe false
        }
}

private class FakeCredentialRepository(
    var lanPairingKeyHex: String? = null,
) : CredentialRepository {
    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        flowOf(stateFor(provider))

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        stateFor(provider)

    private fun stateFor(provider: CredentialProvider): CredentialState =
        CredentialState(
            provider = provider,
            fields =
                when (provider) {
                    CredentialProvider.LAN_SHARE ->
                        listOf(
                            CredentialFieldState(
                                field = CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                                status =
                                    if (lanPairingKeyHex == null) {
                                        StoredCredentialStatus.Missing
                                    } else {
                                        StoredCredentialStatus.Present
                                    },
                            ),
                        )
                    else -> emptyList()
                },
        )

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult {
        if (authorization is CredentialReadAuthorization.Denied) {
            return CredentialSecretReadResult.Unauthorized(authorization.reason)
        }
        return when (field) {
            CredentialField.LAN_SHARE_PAIRING_KEY_HEX ->
                lanPairingKeyHex?.let(CredentialSecretReadResult::Present) ?: CredentialSecretReadResult.Missing
            else -> CredentialSecretReadResult.Missing
        }
    }

    override suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) {
        when (field) {
            CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> lanPairingKeyHex = value
            else -> Unit
        }
    }
}

private object AuthorizedSecuritySessionPolicy : SecuritySessionPolicy {
    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
        CredentialReadAuthorization.Authorized

    override suspend fun isAppLockSatisfied(): Boolean = true
}

private object LockedSecuritySessionPolicy : SecuritySessionPolicy {
    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
        CredentialReadAuthorization.Denied(CredentialReadDenialReason.SecuritySessionLocked)

    override suspend fun isAppLockSatisfied(): Boolean = false
}
