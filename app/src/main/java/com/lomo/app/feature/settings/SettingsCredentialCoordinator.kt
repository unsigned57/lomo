package com.lomo.app.feature.settings

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

class SettingsCredentialCoordinator
    @Inject
    constructor(
        private val credentialRepository: CredentialRepository,
    ) {
        private val statusStates =
            linkedMapOf<
                Pair<CredentialProvider, CredentialField>,
                MutableStateFlow<StoredCredentialStatus>,
            >()

        fun statusState(
            provider: CredentialProvider,
            field: CredentialField,
        ): StateFlow<StoredCredentialStatus> =
            statusStateFor(provider, field).asStateFlow()

        suspend fun refreshCredentialState(provider: CredentialProvider): CredentialState =
            credentialRepository.credentialState(provider).also(::publishCredentialState)

        suspend fun writeSecret(
            field: CredentialField,
            value: String,
        ) {
            credentialRepository.writeSecret(field, value)
            refreshCredentialState(field.provider())
        }

        private fun publishCredentialState(state: CredentialState) {
            CredentialField.values()
                .asSequence()
                .filter { field -> field.provider() == state.provider }
                .forEach { field ->
                    statusStateFor(state.provider, field).value = state.statusFor(field)
                }
        }

        private fun statusStateFor(
            provider: CredentialProvider,
            field: CredentialField,
        ): MutableStateFlow<StoredCredentialStatus> =
            statusStates.getOrPut(provider to field) {
                MutableStateFlow(StoredCredentialStatus.Missing)
            }

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
