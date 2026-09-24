package com.lomo.app.feature.settings

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.provider
import com.lomo.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class SettingsCredentialCoordinator(
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
            refreshCredentialState(field.provider)
        }

        private fun publishCredentialState(state: CredentialState) {
            CredentialField.values()
                .asSequence()
                .filter { field -> field.provider == state.provider }
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

