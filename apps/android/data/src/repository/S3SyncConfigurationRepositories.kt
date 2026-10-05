package com.lomo.data.repository

import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.toS3SyncState
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.isConfigured
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class S3SyncConfigurationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val cycleStatus: RustSyncCycleStatusStore,
) : S3SyncConfigurationRepository {
    override fun isS3SyncEnabled(): Flow<Boolean> = dataStore.s3SyncEnabled

    override fun getEndpointUrl(): Flow<String?> = dataStore.s3EndpointUrl

    override fun getRegion(): Flow<String?> = dataStore.s3Region

    override fun getBucket(): Flow<String?> = dataStore.s3Bucket

    override fun getPrefix(): Flow<String?> = dataStore.s3Prefix

    override fun getLocalSyncDirectory(): Flow<String?> = dataStore.s3LocalSyncDirectory

    override fun getPathStyle(): Flow<S3PathStyle> = dataStore.s3PathStyle.map(::s3PathStyleFromPreference)

    override fun getEncryptionMode(): Flow<S3EncryptionMode> =
        dataStore.s3EncryptionMode.map(::s3EncryptionModeFromPreference)

    override fun getRcloneFilenameEncryption(): Flow<S3RcloneFilenameEncryption> =
        dataStore.s3RcloneFilenameEncryption.map(::s3RcloneFilenameEncryptionFromPreference)

    override fun getRcloneFilenameEncoding(): Flow<S3RcloneFilenameEncoding> =
        dataStore.s3RcloneFilenameEncoding.map(::s3RcloneFilenameEncodingFromPreference)

    override fun getRcloneDirectoryNameEncryption(): Flow<Boolean> =
        dataStore.s3RcloneDirectoryNameEncryption

    override fun getRcloneDataEncryptionEnabled(): Flow<Boolean> =
        dataStore.s3RcloneDataEncryptionEnabled

    override fun getRcloneEncryptedSuffix(): Flow<String> = dataStore.s3RcloneEncryptedSuffix

    override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.s3AutoSyncEnabled

    override fun getAutoSyncInterval(): Flow<String> = dataStore.s3AutoSyncInterval

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.s3SyncOnRefresh

    /**
     * Last-successful sync timestamp from the durable cycle record (`cycle_state.rec`).
     * The `s3LastSyncTime` DataStore write path is retired.
     */
    override fun observeLastSyncTimeMillis(): Flow<Long?> =
        cycleStatus.observe().map { status -> status?.lastSuccessfulAtMs }
}

/**
 * S3 config writes. Endpoint, region, bucket, prefix and access-key id are canonical remote
 * identity (`SyncBackendConfig::canonical_identity`): changing any of them invalidates the
 * durable `.lomo/sync/v1` tree minted under the old identity, so [SyncIdentityResetPolicy]
 * disposes it before the write lands. Re-writing the stored value and non-identity settings
 * (secret keys, session token, local directory, path style, encryption, autosync) leave
 * durable state untouched.
 */
class S3SyncConfigurationMutationRepositoryImpl(
    private val dataStore: LomoDataStore,
    private val credentialRepository: CredentialRepository,
    private val credentialStore: S3CredentialStore,
    private val identityReset: SyncIdentityResetPolicy,
) : S3SyncConfigurationMutationRepository {
    override suspend fun setEndpointUrl(url: String) {
        val normalized = url.trim()
        resetIfIdentityChanged(dataStore.s3EndpointUrl.first().orEmpty(), normalized)
        dataStore.updateS3EndpointUrl(normalized)
    }

    override suspend fun setRegion(region: String) {
        val normalized = region.trim()
        resetIfIdentityChanged(dataStore.s3Region.first().orEmpty(), normalized)
        dataStore.updateS3Region(normalized)
    }

    override suspend fun setBucket(bucket: String) {
        val normalized = bucket.trim()
        resetIfIdentityChanged(dataStore.s3Bucket.first().orEmpty(), normalized)
        dataStore.updateS3Bucket(normalized)
    }

    override suspend fun setPrefix(prefix: String) {
        val normalized = prefix.trim().trim('/')
        resetIfIdentityChanged(dataStore.s3Prefix.first().orEmpty(), normalized)
        dataStore.updateS3Prefix(normalized)
    }

    override suspend fun setLocalSyncDirectory(pathOrUri: String) {
        dataStore.updateS3LocalSyncDirectory(pathOrUri.trim())
    }

    override suspend fun clearLocalSyncDirectory() {
        dataStore.updateS3LocalSyncDirectory(null)
    }

    override suspend fun setAccessKeyId(accessKeyId: String) {
        // The access-key id is non-secret canonical identity; compare through the credential
        // store so re-writing the same key does not wipe the durable sync tree.
        resetIfIdentityChanged(
            credentialStore.getSecret(CredentialField.S3_ACCESS_KEY_ID).orEmpty(),
            accessKeyId,
        )
        credentialRepository.writeSecret(CredentialField.S3_ACCESS_KEY_ID, accessKeyId)
    }

    override suspend fun setSecretAccessKey(secretAccessKey: String) {
        credentialRepository.writeSecret(CredentialField.S3_SECRET_ACCESS_KEY, secretAccessKey)
    }

    override suspend fun setSessionToken(sessionToken: String) {
        credentialRepository.writeSecret(CredentialField.S3_SESSION_TOKEN, sessionToken)
    }

    override suspend fun setPathStyle(pathStyle: S3PathStyle) {
        dataStore.updateS3PathStyle(pathStyle.preferenceValue)
    }

    override suspend fun setEncryptionMode(mode: S3EncryptionMode) {
        dataStore.updateS3EncryptionMode(mode.preferenceValue)
    }

    override suspend fun setRcloneFilenameEncryption(mode: S3RcloneFilenameEncryption) {
        dataStore.updateS3RcloneFilenameEncryption(mode.preferenceValue)
    }

    override suspend fun setRcloneFilenameEncoding(encoding: S3RcloneFilenameEncoding) {
        dataStore.updateS3RcloneFilenameEncoding(encoding.preferenceValue)
    }

    override suspend fun setRcloneDirectoryNameEncryption(enabled: Boolean) {
        dataStore.updateS3RcloneDirectoryNameEncryption(enabled)
    }

    override suspend fun setRcloneDataEncryptionEnabled(enabled: Boolean) {
        dataStore.updateS3RcloneDataEncryptionEnabled(enabled)
    }

    override suspend fun setRcloneEncryptedSuffix(suffix: String) {
        dataStore.updateS3RcloneEncryptedSuffix(s3RcloneEncryptedSuffixToPreference(suffix))
    }

    override suspend fun setEncryptionPassword(password: String) {
        credentialRepository.writeSecret(CredentialField.S3_ENCRYPTION_PASSWORD, password)
    }

    override suspend fun setEncryptionPassword2(password: String) {
        credentialRepository.writeSecret(CredentialField.S3_ENCRYPTION_PASSWORD2, password)
    }

    override suspend fun getAccessKeyStatus(): StoredCredentialStatus =
        credentialState().statusFor(CredentialField.S3_ACCESS_KEY_ID)

    override suspend fun getSecretAccessKeyStatus(): StoredCredentialStatus =
        credentialState().statusFor(CredentialField.S3_SECRET_ACCESS_KEY)

    override suspend fun getSessionTokenStatus(): StoredCredentialStatus =
        credentialState().statusFor(CredentialField.S3_SESSION_TOKEN)

    override suspend fun getEncryptionPasswordStatus(): StoredCredentialStatus =
        credentialState().statusFor(CredentialField.S3_ENCRYPTION_PASSWORD)

    override suspend fun getEncryptionPassword2Status(): StoredCredentialStatus =
        credentialState().statusFor(CredentialField.S3_ENCRYPTION_PASSWORD2)

    override suspend fun getCredentialState(): CredentialState = credentialState()

    override suspend fun isAccessKeyConfigured(): Boolean = getAccessKeyStatus().isConfigured

    override suspend fun isSecretAccessKeyConfigured(): Boolean = getSecretAccessKeyStatus().isConfigured

    override suspend fun isSessionTokenConfigured(): Boolean = getSessionTokenStatus().isConfigured

    override suspend fun isEncryptionPasswordConfigured(): Boolean =
        getEncryptionPasswordStatus().isConfigured

    override suspend fun isEncryptionPassword2Configured(): Boolean =
        getEncryptionPassword2Status().isConfigured

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.updateS3AutoSyncEnabled(enabled)
    }

    override suspend fun setAutoSyncInterval(interval: String) {
        dataStore.updateS3AutoSyncInterval(interval)
    }

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
        dataStore.updateS3SyncOnRefresh(enabled)
    }

    /** Canonical-identity write guard: disposal runs before the mutation lands, never on a no-op re-write. */
    private suspend fun resetIfIdentityChanged(
        previous: String,
        next: String,
    ) {
        if (previous != next) {
            identityReset.resetIdentityScopedSyncState()
        }
    }

    private suspend fun credentialState(): CredentialState =
        credentialRepository.credentialState(CredentialProvider.S3)
}

/** Durable sync state for S3 — real cycle-record reads, never an in-memory `Idle` seed. */
class S3SyncStateRepositoryImpl(
    private val cycleStatus: RustSyncCycleStatusStore,
) : S3SyncStateRepository {
    override fun syncState(): Flow<S3SyncState> =
        cycleStatus.observe().map { status -> status.toS3SyncState() }
}

internal val S3PathStyle.preferenceValue: String
    get() = name.lowercase(java.util.Locale.ROOT)

internal fun s3PathStyleFromPreference(value: String): S3PathStyle =
    S3PathStyle.entries.firstOrNull { it.preferenceValue == value.lowercase(java.util.Locale.ROOT) }
        ?: S3PathStyle.AUTO

internal val S3EncryptionMode.preferenceValue: String
    get() = name.lowercase(java.util.Locale.ROOT)

internal fun s3EncryptionModeFromPreference(value: String): S3EncryptionMode =
    S3EncryptionMode.entries.firstOrNull { it.preferenceValue == value.lowercase(java.util.Locale.ROOT) }
        ?: S3EncryptionMode.NONE

internal val S3RcloneFilenameEncryption.preferenceValue: String
    get() = name.lowercase(java.util.Locale.ROOT)

internal fun s3RcloneFilenameEncryptionFromPreference(value: String): S3RcloneFilenameEncryption =
    S3RcloneFilenameEncryption.entries.firstOrNull {
        it.preferenceValue == value.lowercase(java.util.Locale.ROOT)
    } ?: S3RcloneFilenameEncryption.STANDARD

internal val S3RcloneFilenameEncoding.preferenceValue: String
    get() = name.lowercase(java.util.Locale.ROOT)

internal fun s3RcloneFilenameEncodingFromPreference(value: String): S3RcloneFilenameEncoding =
    S3RcloneFilenameEncoding.entries.firstOrNull {
        it.preferenceValue == value.lowercase(java.util.Locale.ROOT)
    } ?: S3RcloneFilenameEncoding.BASE64

internal fun s3RcloneEncryptedSuffixFromPreference(value: String): String {
    val normalized = value.trim()
    return when {
        normalized.isBlank() -> ".bin"
        normalized.equals("none", ignoreCase = true) -> ""
        normalized.startsWith(".") -> normalized
        else -> ".$normalized"
    }
}

internal fun s3RcloneEncryptedSuffixToPreference(value: String): String =
    value.trim().ifBlank { "none" }
