package com.lomo.domain.repository

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.CredentialSecretReadResult
import kotlinx.coroutines.flow.Flow

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
    )
}

interface SecuritySessionPolicy {
    suspend fun authorizeCredentialRead(): CredentialReadAuthorization
}

interface SecuritySessionController {
    fun markCredentialReadsAuthorized()

    fun markCredentialReadsLocked()
}
