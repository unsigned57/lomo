package com.lomo.data.security

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.model.CredentialSecretReadResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: DefaultCredentialRepository
 * - Owning layer: data/security implementing domain credential boundary
 * - Priority tier: P0
 * - Capability: expose Git, WebDAV, S3, and LAN credential state through one repository and gate secret reads by security session authorization.
 *
 * Scenarios:
 * - Given LAN sharing has a pairing key, when credential state is requested, then LAN is reported by the unified credential repository.
 * - Given a provider credential is unreadable, when credential state is requested, then unreadable is surfaced instead of flattened to missing.
 * - Given a caller reads secret material without credential-read authorization, when the repository is queried, then the result is Unauthorized and no secret is returned.
 * - Given a caller reads secret material with authorization, when the stored LAN key exists, then the key is returned through the credential repository.
 *
 * Observable outcomes:
 * - CredentialState field statuses and CredentialSecretReadResult values.
 *
 * TDD proof:
 * - Fails before the fix because DefaultCredentialRepository and LAN secure credential storage do not exist.
 *
 * Excludes:
 * - Android Keystore cryptography, settings UI rendering, and migration archive encryption format.
 */
class UnifiedCredentialRepositoryTest : DataFunSpec() {
    init {
        test("given lan key and provider failures when credential state is requested then unified statuses are returned") {
            val repository =
                credentialRepository(
                    lanStore =
                        UnifiedFakeSecureStringStore(
                            mapOf(LAN_KEY to SecureStringReadResult.Present("abcdef")),
                        ),
                    s3Store =
                        UnifiedFakeSecureStringStore(
                            mapOf(
                                "s3_access_key_id" to SecureStringReadResult.Present("access"),
                                "s3_secret_access_key" to
                                    SecureStringReadResult.Unreadable(IllegalStateException("locked")),
                            ),
                        ),
                )

            val lanState = repository.credentialState(CredentialProvider.LAN_SHARE)
            val s3State = repository.credentialState(CredentialProvider.S3)

            lanState.fields.shouldContainExactly(
                CredentialFieldState(CredentialField.LAN_SHARE_PAIRING_KEY_HEX, StoredCredentialStatus.Present),
            )
            lanState.isConfigured shouldBe true
            s3State.fields.shouldContainExactly(
                listOf(
                    CredentialFieldState(CredentialField.S3_ACCESS_KEY_ID, StoredCredentialStatus.Present),
                    CredentialFieldState(CredentialField.S3_SECRET_ACCESS_KEY, StoredCredentialStatus.Unreadable),
                    CredentialFieldState(CredentialField.S3_SESSION_TOKEN, StoredCredentialStatus.Missing),
                    CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD, StoredCredentialStatus.Missing),
                    CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD2, StoredCredentialStatus.Missing),
                ),
            )
        }

        test("given credential read is unauthorized when lan key is requested then secret material is not returned") {
            val repository =
                credentialRepository(
                    lanStore =
                        UnifiedFakeSecureStringStore(
                            mapOf(LAN_KEY to SecureStringReadResult.Present("abcdef")),
                        ),
                )

            val result =
                repository.readSecret(
                    field = CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                    authorization =
                        CredentialReadAuthorization.Denied(
                            CredentialReadDenialReason.SecuritySessionLocked,
                        ),
                )

            result shouldBe CredentialSecretReadResult.Unauthorized(CredentialReadDenialReason.SecuritySessionLocked)
        }

        test("given credential read is authorized when lan key is requested then secret material is returned") {
            val repository =
                credentialRepository(
                    lanStore =
                        UnifiedFakeSecureStringStore(
                            mapOf(LAN_KEY to SecureStringReadResult.Present("abcdef")),
                        ),
                )

            val result =
                repository.readSecret(
                    field = CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                    authorization = CredentialReadAuthorization.Authorized,
                )

            result shouldBe CredentialSecretReadResult.Present("abcdef")
        }
    }

    private fun credentialRepository(
        gitStore: SecureStringStore = UnifiedFakeSecureStringStore(emptyMap()),
        webDavStore: SecureStringStore = UnifiedFakeSecureStringStore(emptyMap()),
        s3Store: SecureStringStore = UnifiedFakeSecureStringStore(emptyMap()),
        lanStore: SecureStringStore = UnifiedFakeSecureStringStore(emptyMap()),
    ): DefaultCredentialRepository =
        DefaultCredentialRepository(
            gitCredentialStore = GitCredentialStore(gitStore),
            webDavCredentialStore = WebDavCredentialStore(webDavStore),
            s3CredentialStore = S3CredentialStore(s3Store),
            lanCredentialStore = LanShareCredentialStore(lanStore),
        )
}

private const val LAN_KEY = "lan_share_pairing_key_hex"

private class UnifiedFakeSecureStringStore(
    private val reads: Map<String, SecureStringReadResult>,
) : SecureStringStore {
    private val writes = mutableMapOf<String, String?>()

    override fun readString(key: String): SecureStringReadResult =
        writes[key]?.let(SecureStringReadResult::Present) ?: reads[key] ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) {
        writes[key] = value
    }
}
