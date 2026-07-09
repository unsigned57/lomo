package com.lomo.domain.model

sealed interface CredentialReadAuthorization {
    data object Authorized : CredentialReadAuthorization

    data class Denied(
        val reason: CredentialReadDenialReason,
    ) : CredentialReadAuthorization
}

enum class CredentialReadDenialReason {
    SecuritySessionLocked,
}

sealed interface CredentialSecretReadResult {
    data object Missing : CredentialSecretReadResult

    data class Present(
        val value: String,
    ) : CredentialSecretReadResult

    data object Unreadable : CredentialSecretReadResult

    data class Unauthorized(
        val reason: CredentialReadDenialReason,
    ) : CredentialSecretReadResult
}

class CredentialSecretReadException(
    val result: CredentialSecretReadResult,
) : IllegalStateException(
        when (result) {
            CredentialSecretReadResult.Missing -> "Credential secret is missing"
            is CredentialSecretReadResult.Present -> "Credential secret is present"
            CredentialSecretReadResult.Unreadable -> "Credential secret is unreadable"
            is CredentialSecretReadResult.Unauthorized -> "Credential secret read denied: ${result.reason}"
        },
    )
