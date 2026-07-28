package com.lomo.data.engine.sync

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.runBlocking

/**
 * Post P5-13 Keystore edge for [RustSyncSecretSupplier]: maps field-key names to
 * [CredentialField] and returns secret bytes for ephemeral lease issue only.
 */
class CredentialSecretMaterialSource(
    private val credentialRepository: CredentialRepository,
    private val securitySessionPolicy: SecuritySessionPolicy,
) : SecretMaterialSource {
    override fun readSecretBytes(fieldKey: String): ByteArray? {
        val field =
            // behavior-contract: silent-result-ok: unknown field keys are non-secret miss, not crash
            runCatching { CredentialField.valueOf(fieldKey.trim()) }.getOrNull()
                ?: return null
        val authorization =
            runBlocking { securitySessionPolicy.authorizeCredentialRead() }
        if (authorization is CredentialReadAuthorization.Denied) {
            return null
        }
        return when (
            val result =
                runBlocking {
                    credentialRepository.readSecret(
                        field = field,
                        authorization = authorization,
                    )
                }
        ) {
            CredentialSecretReadResult.Missing -> null
            is CredentialSecretReadResult.Present -> result.value.toByteArray(Charsets.UTF_8)
            CredentialSecretReadResult.Unreadable -> null
            is CredentialSecretReadResult.Unauthorized -> null
        }
    }
}
