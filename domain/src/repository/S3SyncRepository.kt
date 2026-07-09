package com.lomo.domain.repository

import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialFieldState
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.isConfigured
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

interface S3SyncConnectionConfigurationRepository {
    fun isS3SyncEnabled(): Flow<Boolean>

    fun getEndpointUrl(): Flow<String?>

    fun getRegion(): Flow<String?>

    fun getBucket(): Flow<String?>

    fun getPrefix(): Flow<String?>

    fun getLocalSyncDirectory(): Flow<String?>

    fun getPathStyle(): Flow<S3PathStyle>
}

interface S3SyncBehaviorConfigurationRepository {
    fun getEncryptionMode(): Flow<S3EncryptionMode>

    fun getRcloneFilenameEncryption(): Flow<S3RcloneFilenameEncryption>

    fun getRcloneFilenameEncoding(): Flow<S3RcloneFilenameEncoding>

    fun getRcloneDirectoryNameEncryption(): Flow<Boolean>

    fun getRcloneDataEncryptionEnabled(): Flow<Boolean>

    fun getRcloneEncryptedSuffix(): Flow<String>

    fun getAutoSyncEnabled(): Flow<Boolean>

    fun getAutoSyncInterval(): Flow<String>

    fun getSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeLastSyncInstant(): Flow<Instant?> =
        observeLastSyncTimeMillis().map { value ->
            value?.let(Instant::ofEpochMilli)
        }
}

interface S3SyncConfigurationRepository :
    S3SyncConnectionConfigurationRepository,
    S3SyncBehaviorConfigurationRepository

interface S3SyncConnectionMutationRepository {
    suspend fun setS3SyncEnabled(enabled: Boolean)

    suspend fun setEndpointUrl(url: String)

    suspend fun setRegion(region: String)

    suspend fun setBucket(bucket: String)

    suspend fun setPrefix(prefix: String)

    suspend fun setLocalSyncDirectory(pathOrUri: String)

    suspend fun clearLocalSyncDirectory()

    suspend fun setPathStyle(pathStyle: S3PathStyle)
}

interface S3SyncCredentialWriteRepository {
    suspend fun setAccessKeyId(accessKeyId: String)

    suspend fun setSecretAccessKey(secretAccessKey: String)

    suspend fun setSessionToken(sessionToken: String)

    suspend fun setEncryptionPassword(password: String)

    suspend fun setEncryptionPassword2(password: String)
}

interface S3SyncCredentialStatusRepository : S3SyncCredentialConfiguredRepository {
    suspend fun getAccessKeyStatus(): StoredCredentialStatus =
        if (isAccessKeyConfigured()) {
            StoredCredentialStatus.Present
        } else {
            StoredCredentialStatus.Missing
        }

    suspend fun getSecretAccessKeyStatus(): StoredCredentialStatus =
        if (isSecretAccessKeyConfigured()) {
            StoredCredentialStatus.Present
        } else {
            StoredCredentialStatus.Missing
        }

    suspend fun getSessionTokenStatus(): StoredCredentialStatus =
        if (isSessionTokenConfigured()) {
            StoredCredentialStatus.Present
        } else {
            StoredCredentialStatus.Missing
        }

    suspend fun getEncryptionPasswordStatus(): StoredCredentialStatus =
        if (isEncryptionPasswordConfigured()) {
            StoredCredentialStatus.Present
        } else {
            StoredCredentialStatus.Missing
        }

    suspend fun getEncryptionPassword2Status(): StoredCredentialStatus =
        if (isEncryptionPassword2Configured()) {
            StoredCredentialStatus.Present
        } else {
            StoredCredentialStatus.Missing
        }

    suspend fun getCredentialState(): CredentialState =
        CredentialState(
            provider = CredentialProvider.S3,
            fields =
                listOf(
                    CredentialFieldState(CredentialField.S3_ACCESS_KEY_ID, getAccessKeyStatus()),
                    CredentialFieldState(CredentialField.S3_SECRET_ACCESS_KEY, getSecretAccessKeyStatus()),
                    CredentialFieldState(CredentialField.S3_SESSION_TOKEN, getSessionTokenStatus()),
                    CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD, getEncryptionPasswordStatus()),
                    CredentialFieldState(CredentialField.S3_ENCRYPTION_PASSWORD2, getEncryptionPassword2Status()),
                ),
        )

}

interface S3SyncCredentialConfiguredRepository {
    suspend fun isAccessKeyConfigured(): Boolean

    suspend fun isSecretAccessKeyConfigured(): Boolean

    suspend fun isSessionTokenConfigured(): Boolean

    suspend fun isEncryptionPasswordConfigured(): Boolean

    suspend fun isEncryptionPassword2Configured(): Boolean
}

interface S3SyncCredentialMutationRepository :
    S3SyncCredentialWriteRepository,
    S3SyncCredentialStatusRepository,
    S3SyncCredentialConfiguredRepository

interface S3SyncBehaviorMutationRepository {
    suspend fun setEncryptionMode(mode: S3EncryptionMode)

    suspend fun setRcloneFilenameEncryption(mode: S3RcloneFilenameEncryption)

    suspend fun setRcloneFilenameEncoding(encoding: S3RcloneFilenameEncoding)

    suspend fun setRcloneDirectoryNameEncryption(enabled: Boolean)

    suspend fun setRcloneDataEncryptionEnabled(enabled: Boolean)

    suspend fun setRcloneEncryptedSuffix(suffix: String)

    suspend fun setAutoSyncEnabled(enabled: Boolean)

    suspend fun setAutoSyncInterval(interval: String)

    suspend fun setSyncOnRefreshEnabled(enabled: Boolean)
}

interface S3SyncConfigurationMutationRepository :
    S3SyncConnectionMutationRepository,
    S3SyncCredentialMutationRepository,
    S3SyncBehaviorMutationRepository

interface S3SyncOperationRepository {
    suspend fun sync(): S3SyncResult

    suspend fun syncForRefresh(): S3SyncResult = sync()

    suspend fun getStatus(): S3SyncStatus

    suspend fun testConnection(): S3SyncResult
}

interface S3SyncConflictRepository {
    suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): S3SyncResult
}

interface S3SyncReviewRepository {
    suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): S3SyncResult
}

interface S3SyncStateRepository {
    fun syncState(): Flow<S3SyncState>
}

interface S3SyncRepository :
    S3SyncConfigurationRepository,
    S3SyncConfigurationMutationRepository,
    S3SyncOperationRepository,
    S3SyncConflictRepository,
    S3SyncReviewRepository,
    S3SyncStateRepository
