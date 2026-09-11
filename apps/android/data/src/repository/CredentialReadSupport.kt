package com.lomo.data.repository

import com.lomo.domain.model.CredentialField
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy

internal data class RequiredCredentialRead(
    val value: String,
)

internal sealed interface OptionalCredentialRead {
    data object Missing : OptionalCredentialRead

    data class Present(
        val value: String,
    ) : OptionalCredentialRead
}

internal suspend fun CredentialRepository.readRequiredCredential(
    field: CredentialField,
    securitySessionPolicy: SecuritySessionPolicy,
    onMissing: () -> Nothing,
    onUnreadable: () -> Nothing,
    onUnauthorized: (CredentialSecretReadResult.Unauthorized) -> Nothing,
): RequiredCredentialRead =
    when (
        val result =
            readSecret(
                field = field,
                authorization = securitySessionPolicy.authorizeCredentialRead(),
            )
    ) {
        CredentialSecretReadResult.Missing -> onMissing()
        is CredentialSecretReadResult.Present ->
            result.value
                .trim()
                .takeIf(String::isNotBlank)
                ?.let(::RequiredCredentialRead)
                ?: onMissing()
        CredentialSecretReadResult.Unreadable -> onUnreadable()
        is CredentialSecretReadResult.Unauthorized -> onUnauthorized(result)
    }

internal suspend fun CredentialRepository.readOptionalCredential(
    field: CredentialField,
    securitySessionPolicy: SecuritySessionPolicy,
    trim: Boolean,
    onUnreadable: () -> Nothing,
    onUnauthorized: (CredentialSecretReadResult.Unauthorized) -> Nothing,
): OptionalCredentialRead =
    when (
        val result =
            readSecret(
                field = field,
                authorization = securitySessionPolicy.authorizeCredentialRead(),
            )
    ) {
        CredentialSecretReadResult.Missing -> OptionalCredentialRead.Missing
        is CredentialSecretReadResult.Present -> {
            val value = if (trim) result.value.trim() else result.value
            value
                .takeIf(String::isNotBlank)
                ?.let(OptionalCredentialRead::Present)
                ?: OptionalCredentialRead.Missing
        }
        CredentialSecretReadResult.Unreadable -> onUnreadable()
        is CredentialSecretReadResult.Unauthorized -> onUnauthorized(result)
    }
