package com.lomo.domain.model

enum class StoredCredentialStatus {
    Missing,
    Present,
    Unreadable,
    Invalid,
}

val StoredCredentialStatus.isConfigured: Boolean
    get() = this == StoredCredentialStatus.Present

val StoredCredentialStatus.isMissing: Boolean
    get() = this == StoredCredentialStatus.Missing

enum class CredentialProvider {
    GIT,
    WEBDAV,
    S3,
    LAN_SHARE,
}

enum class CredentialField {
    GIT_TOKEN,
    WEBDAV_USERNAME,
    WEBDAV_PASSWORD,
    S3_ACCESS_KEY_ID,
    S3_SECRET_ACCESS_KEY,
    S3_SESSION_TOKEN,
    S3_ENCRYPTION_PASSWORD,
    S3_ENCRYPTION_PASSWORD2,
    LAN_SHARE_PAIRING_KEY_HEX,
}

val CredentialField.isRequiredForProviderConfiguration: Boolean
    get() =
        when (this) {
            CredentialField.GIT_TOKEN,
            CredentialField.WEBDAV_USERNAME,
            CredentialField.WEBDAV_PASSWORD,
            CredentialField.S3_ACCESS_KEY_ID,
            CredentialField.S3_SECRET_ACCESS_KEY,
            CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
            -> true
            CredentialField.S3_SESSION_TOKEN,
            CredentialField.S3_ENCRYPTION_PASSWORD,
            CredentialField.S3_ENCRYPTION_PASSWORD2,
            -> false
        }

private val CredentialProvider.requiredFields: Set<CredentialField>
    get() =
        when (this) {
            CredentialProvider.GIT -> setOf(CredentialField.GIT_TOKEN)
            CredentialProvider.WEBDAV ->
                setOf(
                    CredentialField.WEBDAV_USERNAME,
                    CredentialField.WEBDAV_PASSWORD,
                )
            CredentialProvider.S3 ->
                setOf(
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialField.S3_SECRET_ACCESS_KEY,
                )
            CredentialProvider.LAN_SHARE -> setOf(CredentialField.LAN_SHARE_PAIRING_KEY_HEX)
        }

data class CredentialFieldState(
    val field: CredentialField,
    val status: StoredCredentialStatus,
)

data class CredentialState(
    val provider: CredentialProvider,
    val fields: List<CredentialFieldState>,
) {
    val readinessStatus: StoredCredentialStatus = aggregateReadinessStatus()

    val healthStatus: StoredCredentialStatus = aggregateHealthStatus()

    val status: StoredCredentialStatus = healthStatus

    val isConfigured: Boolean = readinessStatus.isConfigured

    private fun aggregateReadinessStatus(): StoredCredentialStatus {
        if (fields.isEmpty()) {
            return StoredCredentialStatus.Missing
        }
        val fieldsByName = fields.associateBy(CredentialFieldState::field)
        val requiredStatuses =
            provider.requiredFields.map { field ->
                fieldsByName[field]?.status ?: StoredCredentialStatus.Missing
            }
        return aggregateStatuses(requiredStatuses)
    }

    private fun aggregateHealthStatus(): StoredCredentialStatus =
        when {
            fields.any { it.status == StoredCredentialStatus.Unreadable } -> StoredCredentialStatus.Unreadable
            fields.any { it.status == StoredCredentialStatus.Invalid } -> StoredCredentialStatus.Invalid
            else -> readinessStatus
        }

    private fun aggregateStatuses(statuses: List<StoredCredentialStatus>): StoredCredentialStatus =
        when {
            statuses.isEmpty() -> StoredCredentialStatus.Missing
            statuses.any { it == StoredCredentialStatus.Unreadable } -> StoredCredentialStatus.Unreadable
            statuses.any { it == StoredCredentialStatus.Invalid } -> StoredCredentialStatus.Invalid
            statuses.any { it == StoredCredentialStatus.Missing } -> StoredCredentialStatus.Missing
            else -> StoredCredentialStatus.Present
        }
}
