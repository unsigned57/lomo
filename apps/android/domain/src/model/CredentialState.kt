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
}

enum class CredentialField {
    GIT_TOKEN,
    GIT_USERNAME,
    WEBDAV_USERNAME,
    WEBDAV_PASSWORD,
    S3_ACCESS_KEY_ID,
    S3_SECRET_ACCESS_KEY,
    S3_SESSION_TOKEN,
    S3_ENCRYPTION_PASSWORD,
    S3_ENCRYPTION_PASSWORD2,
}

val CredentialField.provider: CredentialProvider
    get() =
        when (this) {
            CredentialField.GIT_TOKEN,
            CredentialField.GIT_USERNAME,
            -> CredentialProvider.GIT
            CredentialField.WEBDAV_USERNAME,
            CredentialField.WEBDAV_PASSWORD,
            -> CredentialProvider.WEBDAV
            CredentialField.S3_ACCESS_KEY_ID,
            CredentialField.S3_SECRET_ACCESS_KEY,
            CredentialField.S3_SESSION_TOKEN,
            CredentialField.S3_ENCRYPTION_PASSWORD,
            CredentialField.S3_ENCRYPTION_PASSWORD2,
            -> CredentialProvider.S3
        }

fun CredentialProvider.identityField(): CredentialField? =
    when (this) {
        CredentialProvider.GIT -> CredentialField.GIT_USERNAME
        CredentialProvider.WEBDAV -> CredentialField.WEBDAV_USERNAME
        CredentialProvider.S3 -> CredentialField.S3_ACCESS_KEY_ID
    }

fun CredentialProvider.secretField(): CredentialField =
    when (this) {
        CredentialProvider.GIT -> CredentialField.GIT_TOKEN
        CredentialProvider.WEBDAV -> CredentialField.WEBDAV_PASSWORD
        CredentialProvider.S3 -> CredentialField.S3_SECRET_ACCESS_KEY
    }

val CredentialField.isRequiredForProviderConfiguration: Boolean
    get() =
        when (this) {
            CredentialField.GIT_TOKEN,
            CredentialField.WEBDAV_USERNAME,
            CredentialField.WEBDAV_PASSWORD,
            CredentialField.S3_ACCESS_KEY_ID,
            CredentialField.S3_SECRET_ACCESS_KEY,
            -> true
            CredentialField.GIT_USERNAME,
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

    fun statusFor(field: CredentialField): StoredCredentialStatus =
        fields.firstOrNull { state -> state.field == field }?.status ?: StoredCredentialStatus.Missing

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
