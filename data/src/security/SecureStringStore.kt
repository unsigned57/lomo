package com.lomo.data.security

import com.lomo.domain.model.StoredCredentialStatus

internal interface SecureStringStore {
    fun readString(key: String): SecureStringReadResult

    fun getString(key: String): String? =
        when (val result = readString(key)) {
            SecureStringReadResult.Missing -> null
            is SecureStringReadResult.Present -> result.value
            is SecureStringReadResult.Unreadable -> throw SecureStringReadException(key, result.cause)
        }

    fun putString(
        key: String,
        value: String?,
    )
}

internal sealed interface SecureStringReadResult {
    data object Missing : SecureStringReadResult

    data class Present(
        val value: String,
    ) : SecureStringReadResult

    data class Unreadable(
        val cause: Throwable,
    ) : SecureStringReadResult
}

internal class SecureStringReadException(
    val key: String,
    cause: Throwable,
) : IllegalStateException("Failed to read secure string for key=$key", cause)

internal fun SecureStringStore.credentialStatus(key: String): StoredCredentialStatus =
    when (val result = readString(key)) {
        SecureStringReadResult.Missing -> StoredCredentialStatus.Missing
        is SecureStringReadResult.Present ->
            if (result.value.isBlank()) {
                StoredCredentialStatus.Invalid
            } else {
                StoredCredentialStatus.Present
            }
        is SecureStringReadResult.Unreadable -> StoredCredentialStatus.Unreadable
    }
