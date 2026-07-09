package com.lomo.app.testing.fakes

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.CredentialReadAuthorization
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCredentialRepository(
    initialStates: Map<CredentialProvider, CredentialState> = emptyMap(),
) : CredentialRepository {
    private val providerStates: Map<CredentialProvider, MutableStateFlow<CredentialState>> =
        CredentialProvider.values().associateWith { provider ->
            MutableStateFlow(initialStates[provider] ?: CredentialState(provider, emptyList()))
        }
    private val secrets = mutableMapOf<CredentialField, String>()

    override fun observeCredentialState(provider: CredentialProvider): Flow<CredentialState> =
        providerStates.getValue(provider).asStateFlow()

    override suspend fun credentialState(provider: CredentialProvider): CredentialState =
        providerStates.getValue(provider).value

    override suspend fun readSecret(
        field: CredentialField,
        authorization: CredentialReadAuthorization,
    ): CredentialSecretReadResult =
        when (authorization) {
            CredentialReadAuthorization.Authorized ->
                when (fieldStatus(field)) {
                    StoredCredentialStatus.Missing -> CredentialSecretReadResult.Missing
                    StoredCredentialStatus.Present ->
                        CredentialSecretReadResult.Present(secrets[field].orEmpty())
                    StoredCredentialStatus.Unreadable,
                    StoredCredentialStatus.Invalid,
                    -> CredentialSecretReadResult.Unreadable
                }
            is CredentialReadAuthorization.Denied -> CredentialSecretReadResult.Unauthorized(authorization.reason)
        }

    override suspend fun writeSecret(
        field: CredentialField,
        value: String?,
    ) {
        if (value == null) {
            secrets.remove(field)
            setFieldStatus(field, StoredCredentialStatus.Missing)
        } else {
            secrets[field] = value
            setFieldStatus(field, StoredCredentialStatus.Present)
        }
    }

    fun setFieldStatus(
        field: CredentialField,
        status: StoredCredentialStatus,
    ) {
        val provider = field.provider()
        val current = providerStates.getValue(provider).value
        val nextFields =
            current
                .fields
                .filterNot { state -> state.field == field } + CredentialFieldState(field, status)
        providerStates.getValue(provider).value = CredentialState(provider, nextFields)
    }

    fun reset() {
        secrets.clear()
        providerStates.forEach { (provider, state) ->
            state.value = CredentialState(provider, emptyList())
        }
    }

    private fun fieldStatus(field: CredentialField): StoredCredentialStatus =
        providerStates.getValue(field.provider()).value.statusFor(field)
}

private fun CredentialField.provider(): CredentialProvider =
    when (this) {
        CredentialField.GIT_TOKEN -> CredentialProvider.GIT
        CredentialField.WEBDAV_USERNAME,
        CredentialField.WEBDAV_PASSWORD,
        -> CredentialProvider.WEBDAV
        CredentialField.S3_ACCESS_KEY_ID,
        CredentialField.S3_SECRET_ACCESS_KEY,
        CredentialField.S3_SESSION_TOKEN,
        CredentialField.S3_ENCRYPTION_PASSWORD,
        CredentialField.S3_ENCRYPTION_PASSWORD2,
        -> CredentialProvider.S3
        CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> CredentialProvider.LAN_SHARE
    }
