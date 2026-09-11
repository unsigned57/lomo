package com.lomo.data.security

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialReadDenialReason
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.StoredCredentialStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: DefaultCredentialRepository.
 * - Owning layer: data/security.
 * - Priority tier: P0.
 * - Capability: expose Git, WebDAV, and S3 credential state while enforcing read authorization.
 *
 * Scenarios:
 * - Given an unreadable S3 secret, when provider state is queried, then unreadable remains visible.
 * - Given a denied security session, when a Git secret is read, then no secret bytes are returned.
 *
 * Observable outcomes:
 * - Typed credential field states and CredentialSecretReadResult values.
 *
 * TDD proof:
 * - RED during P6-10 when the removed LAN pairing credential still made the repository graph and
 *   test fixture depend on a deleted provider.
 *
 * Excludes:
 * - Android Keystore cryptography, migration archives, and settings UI.
 *
 * Test Change Justification:
 * - Reason category: Stage-6 atomic LAN credential cutover.
 * - Old behavior/assertion being replaced: LAN pairing secret was exposed as a repository provider.
 * - Why old assertion is no longer correct: device trust is Rust-owned and uses a non-exportable
 *   Keystore signing key, so no LAN shared secret exists in CredentialRepository.
 * - Coverage preserved by: provider-state aggregation and authorization denial remain asserted on
 *   the credential providers that still belong to this repository.
 * - Why this is not fitting the test to the implementation: the test protects the public
 *   credential boundary and the explicit removal of shared-secret LAN trust.
 */
class UnifiedCredentialRepositoryTest : DataFunSpec() {
    init {
        test("given unreadable provider secret when state is requested then unreadable remains visible") {
            val repository =
                repository(
                    s3Store =
                        UnifiedCredentialFakeStore(
                            mapOf(
                                "s3_access_key_id" to SecureStringReadResult.Present("access"),
                                "s3_secret_access_key" to
                                    SecureStringReadResult.Unreadable(IllegalStateException("locked")),
                            ),
                        ),
                )

            repository.credentialState(CredentialProvider.S3).fields.shouldContainExactly(
                CredentialFieldState(CredentialField.S3_ACCESS_KEY_ID, StoredCredentialStatus.Present),
                CredentialFieldState(CredentialField.S3_SECRET_ACCESS_KEY, StoredCredentialStatus.Unreadable),
                CredentialFieldState(CredentialField.S3_SESSION_TOKEN, StoredCredentialStatus.Missing),
                CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD, StoredCredentialStatus.Missing),
                CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD2, StoredCredentialStatus.Missing),
            )
        }

        test("given denied security session when secret is read then no secret bytes are returned") {
            val repository =
                repository(
                    gitStore =
                        UnifiedCredentialFakeStore(
                            mapOf("git_token" to SecureStringReadResult.Present("secret")),
                        ),
                )

            repository.readSecret(
                field = CredentialField.GIT_TOKEN,
                authorization =
                    CredentialReadAuthorization.Denied(
                        CredentialReadDenialReason.SecuritySessionLocked,
                    ),
            ) shouldBe CredentialSecretReadResult.Unauthorized(CredentialReadDenialReason.SecuritySessionLocked)
        }
    }

    private fun repository(
        gitStore: SecureStringStore = UnifiedCredentialFakeStore(emptyMap()),
        webDavStore: SecureStringStore = UnifiedCredentialFakeStore(emptyMap()),
        s3Store: SecureStringStore = UnifiedCredentialFakeStore(emptyMap()),
    ): DefaultCredentialRepository =
        DefaultCredentialRepository(
            gitCredentialStore = GitCredentialStore(gitStore),
            webDavCredentialStore = WebDavCredentialStore(webDavStore),
            s3CredentialStore = S3CredentialStore(s3Store),
        )
}

private class UnifiedCredentialFakeStore(
    private val reads: Map<String, SecureStringReadResult>,
) : SecureStringStore {
    override fun readString(key: String): SecureStringReadResult =
        reads[key] ?: SecureStringReadResult.Missing

    override fun putString(
        key: String,
        value: String?,
    ) = Unit
}
