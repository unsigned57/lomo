package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncConfigurationRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
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

        override fun getRcloneEncryptedSuffix(): Flow<String> =
            dataStore.s3RcloneEncryptedSuffix

        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.s3AutoSyncEnabled

        override fun getAutoSyncInterval(): Flow<String> = dataStore.s3AutoSyncInterval

        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.s3SyncOnRefresh

        override fun observeLastSyncTimeMillis(): Flow<Long?> =
            dataStore.s3LastSyncTime.map { stored -> stored.takeIf { it > 0L } }
    }

@Singleton
class S3SyncConfigurationMutationRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialStore: S3CredentialStore,
    ) : S3SyncConfigurationMutationRepository {
        override suspend fun setS3SyncEnabled(enabled: Boolean) {
            dataStore.updateS3SyncEnabled(enabled)
        }

        override suspend fun setEndpointUrl(url: String) {
            dataStore.updateS3EndpointUrl(url.trim())
        }

        override suspend fun setRegion(region: String) {
            dataStore.updateS3Region(region.trim())
        }

        override suspend fun setBucket(bucket: String) {
            dataStore.updateS3Bucket(bucket.trim())
        }

        override suspend fun setPrefix(prefix: String) {
            dataStore.updateS3Prefix(prefix.trim().trim('/'))
        }

        override suspend fun setLocalSyncDirectory(pathOrUri: String) {
            dataStore.updateS3LocalSyncDirectory(pathOrUri.trim())
        }

        override suspend fun clearLocalSyncDirectory() {
            dataStore.updateS3LocalSyncDirectory(null)
        }

        override suspend fun setAccessKeyId(accessKeyId: String) {
            credentialStore.setAccessKeyId(accessKeyId.trim())
        }

        override suspend fun setSecretAccessKey(secretAccessKey: String) {
            credentialStore.setSecretAccessKey(secretAccessKey.trim())
        }

        override suspend fun setSessionToken(sessionToken: String) {
            credentialStore.setSessionToken(sessionToken.trim())
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
            credentialStore.setEncryptionPassword(password.trim())
        }

        override suspend fun setEncryptionPassword2(password: String) {
            credentialStore.setEncryptionPassword2(password.trim())
        }

        override suspend fun isAccessKeyConfigured(): Boolean = !credentialStore.getAccessKeyId().isNullOrBlank()

        override suspend fun isSecretAccessKeyConfigured(): Boolean =
            !credentialStore.getSecretAccessKey().isNullOrBlank()

        override suspend fun isSessionTokenConfigured(): Boolean = !credentialStore.getSessionToken().isNullOrBlank()

        override suspend fun isEncryptionPasswordConfigured(): Boolean =
            !credentialStore.getEncryptionPassword().isNullOrBlank()

        override suspend fun isEncryptionPassword2Configured(): Boolean =
            !credentialStore.getEncryptionPassword2().isNullOrBlank()

        override suspend fun setAutoSyncEnabled(enabled: Boolean) {
            dataStore.updateS3AutoSyncEnabled(enabled)
        }

        override suspend fun setAutoSyncInterval(interval: String) {
            dataStore.updateS3AutoSyncInterval(interval)
        }

        override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
            dataStore.updateS3SyncOnRefresh(enabled)
        }
    }

@Singleton
class S3SyncStateHolder
    @Inject
    constructor() {
        val state = kotlinx.coroutines.flow.MutableStateFlow<S3SyncState>(S3SyncState.Idle)
    }

@Singleton
class S3SyncStateRepositoryImpl
    @Inject
    constructor(
        private val stateHolder: S3SyncStateHolder,
    ) : S3SyncStateRepository {
        override fun syncState(): Flow<S3SyncState> = stateHolder.state
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
