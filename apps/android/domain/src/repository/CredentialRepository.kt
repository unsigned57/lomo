package com.lomo.domain.repository

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.SecuritySessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CredentialRepository {
    fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState>

    suspend fun credentialState(provider: CredentialProvider): CredentialState

    suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult

    suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) = writeSecrets(mapOf(field to value))

    /**
     * Applies [values] (`null` clears) as one atomic unit: the batch must commit without a
     * suspension point between entries, so a pending cancellation lands before the first write
     * or after the last — never between two fields of one call. Bulk restore relies on this:
     * a cancelled import must not leave a mixed old/new credential set.
     */
    suspend fun writeSecrets(values: Map<CredentialField, String?>)
}

interface SecuritySessionPolicy {
    suspend fun authorizeCredentialRead(): CredentialReadAuthorization

    suspend fun current(): SecuritySessionState

    fun observe(): StateFlow<SecuritySessionState>
}

interface SecuritySessionController {
    fun recordAuthenticated()

    fun recordBackgrounded()

    suspend fun refresh()
}

fun interface AuthorizedWorkResume {
    fun onSessionAllowsBackgroundWork()
}
