package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Type: test fixture
 * - Capability: provide pre-built authorized/unauthorized credential authorization values
 *   for tests exercising the CredentialRepository read/write contract.
 * - Scenarios: Given a CredentialReadAuthorization value, when fixture factory is called, then pre-built authorized or denied authorization instances are returned.
 * - Observable outcomes: factory values return configured CredentialReadAuthorization instances
 * - TDD proof: used as building blocks by integration tests; no standalone observable change
 * - Excludes: production authorization, SecuritySessionController, UI integration.
 *
 * Test Change Justification:
 * - Reason category: security session contract extension.
 * - Old behavior/assertion being replaced: fixture policies only implemented credential-read authorization.
 * - Why old assertion is no longer correct: SecuritySessionPolicy now also exposes app-lock satisfaction for tile entry checks.
 * - Coverage preserved by: authorized and locked credential-read fixture behavior remains explicit.
 * - Why this is not fitting the test to the implementation: fixture values model the same security session states.
 */

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object AuthorizedCredentialReadSessionPolicy : SecuritySessionPolicy {
    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
        CredentialReadAuthorization.Authorized

    override suspend fun isAppLockSatisfied(): Boolean = true
}

internal object LockedCredentialReadSessionPolicy : SecuritySessionPolicy {
    override suspend fun authorizeCredentialRead(): CredentialReadAuthorization =
        CredentialReadAuthorization.Denied(com.lomo.domain.model.CredentialReadDenialReason.SecuritySessionLocked)

    override suspend fun isAppLockSatisfied(): Boolean = false
}

internal fun testS3CredentialRepository(
    accessKeyId: CredentialSecretReadResult = CredentialSecretReadResult.Present("access"),
    secretAccessKey: CredentialSecretReadResult = CredentialSecretReadResult.Present("secret"),
    sessionToken: CredentialSecretReadResult = CredentialSecretReadResult.Missing,
    encryptionPassword: CredentialSecretReadResult = CredentialSecretReadResult.Missing,
    encryptionPassword2: CredentialSecretReadResult = CredentialSecretReadResult.Missing,
): TestCredentialRepository =
    TestCredentialRepository(
        mutableMapOf(
            CredentialField.S3_ACCESS_KEY_ID to accessKeyId,
            CredentialField.S3_SECRET_ACCESS_KEY to secretAccessKey,
            CredentialField.S3_SESSION_TOKEN to sessionToken,
            CredentialField.S3_ENCRYPTION_PASSWORD to encryptionPassword,
            CredentialField.S3_ENCRYPTION_PASSWORD2 to encryptionPassword2,
        ),
    )

internal fun testWebDavCredentialRepository(
    username: CredentialSecretReadResult = CredentialSecretReadResult.Present("alice"),
    password: CredentialSecretReadResult = CredentialSecretReadResult.Present("secret"),
): TestCredentialRepository =
    TestCredentialRepository(
        mutableMapOf(
            CredentialField.WEBDAV_USERNAME to username,
            CredentialField.WEBDAV_PASSWORD to password,
        ),
    )

internal fun testGitCredentialRepository(
    token: CredentialSecretReadResult = CredentialSecretReadResult.Present("token"),
): TestCredentialRepository =
    TestCredentialRepository(mutableMapOf(CredentialField.GIT_TOKEN to token))

internal class TestCredentialRepository(
    private val reads: MutableMap<CredentialField, CredentialSecretReadResult>,
) : CredentialRepository {
    fun setRead(
        field: CredentialField,
        result: CredentialSecretReadResult,
    ) {
        reads[field] = result
    }

    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        flowOf(credentialStateFor(provider))

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        credentialStateFor(provider)

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult =
        if (authorization is CredentialReadAuthorization.Denied) {
            CredentialSecretReadResult.Unauthorized(authorization.reason)
        } else {
            reads[field] ?: CredentialSecretReadResult.Missing
        }

    override suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) = Unit

    private fun credentialStateFor(provider: CredentialProvider): CredentialState =
        CredentialState(
            provider = provider,
            fields = provider.fields.map { field -> CredentialFieldState(field, statusFor(reads[field])) },
        )

    private fun statusFor(result: CredentialSecretReadResult?): StoredCredentialStatus =
        when (result) {
            is CredentialSecretReadResult.Present -> StoredCredentialStatus.Present
            CredentialSecretReadResult.Unreadable -> StoredCredentialStatus.Unreadable
            is CredentialSecretReadResult.Unauthorized -> StoredCredentialStatus.Invalid
            CredentialSecretReadResult.Missing,
            null,
            -> StoredCredentialStatus.Missing
        }
}

private val CredentialProvider.fields: List<CredentialField>
    get() =
        when (this) {
            CredentialProvider.GIT -> listOf(CredentialField.GIT_TOKEN)
            CredentialProvider.WEBDAV -> listOf(CredentialField.WEBDAV_USERNAME, CredentialField.WEBDAV_PASSWORD)
            CredentialProvider.S3 ->
                listOf(
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialField.S3_SECRET_ACCESS_KEY,
                    CredentialField.S3_SESSION_TOKEN,
                    CredentialField.S3_ENCRYPTION_PASSWORD,
                    CredentialField.S3_ENCRYPTION_PASSWORD2,
                )
            CredentialProvider.LAN_SHARE -> listOf(CredentialField.LAN_SHARE_PAIRING_KEY_HEX)
        }
