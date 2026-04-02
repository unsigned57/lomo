package com.lomo.domain.usecase

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.flow.Flow

interface S3SyncConnectionObservation {
    fun observeS3SyncEnabled(): Flow<Boolean>

    fun observeEndpointUrl(): Flow<String?>

    fun observeRegion(): Flow<String?>

    fun observeBucket(): Flow<String?>

    fun observePrefix(): Flow<String?>

    fun observeLocalSyncDirectory(): Flow<String?>

    fun observePathStyle(): Flow<S3PathStyle>
}

interface S3SyncBehaviorObservation {
    fun observeEncryptionMode(): Flow<S3EncryptionMode>

    fun observeAutoSyncEnabled(): Flow<Boolean>

    fun observeAutoSyncInterval(): Flow<String>

    fun observeSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeSyncState(): Flow<S3SyncState>
}

interface S3SyncCredentialObservation {
    suspend fun isAccessKeyConfigured(): Boolean

    suspend fun isSecretAccessKeyConfigured(): Boolean

    suspend fun isSessionTokenConfigured(): Boolean

    suspend fun isEncryptionPasswordConfigured(): Boolean
}

interface S3SyncConnectionMutation {
    suspend fun updateS3SyncEnabled(enabled: Boolean)

    suspend fun updateEndpointUrl(url: String)

    suspend fun updateRegion(region: String)

    suspend fun updateBucket(bucket: String)

    suspend fun updatePrefix(prefix: String)

    suspend fun updateLocalSyncDirectory(pathOrUri: String)

    suspend fun clearLocalSyncDirectory()

    suspend fun updatePathStyle(pathStyle: S3PathStyle)
}

interface S3SyncCredentialMutation {
    suspend fun updateAccessKeyId(accessKeyId: String)

    suspend fun updateSecretAccessKey(secretAccessKey: String)

    suspend fun updateSessionToken(sessionToken: String)

    suspend fun updateEncryptionPassword(password: String)
}

interface S3SyncBehaviorMutation {
    suspend fun updateEncryptionMode(mode: S3EncryptionMode)

    suspend fun updateAutoSyncEnabled(enabled: Boolean)

    suspend fun updateAutoSyncInterval(interval: String)

    suspend fun updateSyncOnRefreshEnabled(enabled: Boolean)
}

interface S3SyncSettingsActions {
    suspend fun triggerSyncNow()

    suspend fun testConnection(): S3SyncResult
}

class S3SyncSettingsUseCase(
    s3SyncRepository: S3SyncRepository,
    syncPolicyRepository: SyncPolicyRepository,
    syncAndRebuildUseCase: SyncAndRebuildUseCase,
) : S3SyncConnectionObservation by
    S3SyncConnectionObservationImpl(s3SyncRepository),
    S3SyncBehaviorObservation by
        S3SyncBehaviorObservationImpl(s3SyncRepository),
    S3SyncCredentialObservation by
        S3SyncCredentialObservationImpl(s3SyncRepository),
    S3SyncConnectionMutation by
        S3SyncConnectionMutationImpl(
            s3SyncRepository = s3SyncRepository,
            syncPolicyRepository = syncPolicyRepository,
        ),
    S3SyncCredentialMutation by
        S3SyncCredentialMutationImpl(s3SyncRepository),
    S3SyncBehaviorMutation by
        S3SyncBehaviorMutationImpl(
            s3SyncRepository = s3SyncRepository,
            syncPolicyRepository = syncPolicyRepository,
        ),
    S3SyncSettingsActions by
        S3SyncSettingsActionsImpl(
            s3SyncRepository = s3SyncRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

private class S3SyncConnectionObservationImpl(
    private val s3SyncRepository: S3SyncRepository,
) : S3SyncConnectionObservation {
    override fun observeS3SyncEnabled(): Flow<Boolean> = s3SyncRepository.isS3SyncEnabled()

    override fun observeEndpointUrl(): Flow<String?> = s3SyncRepository.getEndpointUrl()

    override fun observeRegion(): Flow<String?> = s3SyncRepository.getRegion()

    override fun observeBucket(): Flow<String?> = s3SyncRepository.getBucket()

    override fun observePrefix(): Flow<String?> = s3SyncRepository.getPrefix()

    override fun observeLocalSyncDirectory(): Flow<String?> = s3SyncRepository.getLocalSyncDirectory()

    override fun observePathStyle(): Flow<S3PathStyle> = s3SyncRepository.getPathStyle()
}

private class S3SyncBehaviorObservationImpl(
    private val s3SyncRepository: S3SyncRepository,
) : S3SyncBehaviorObservation {
    override fun observeEncryptionMode(): Flow<S3EncryptionMode> = s3SyncRepository.getEncryptionMode()

    override fun observeAutoSyncEnabled(): Flow<Boolean> = s3SyncRepository.getAutoSyncEnabled()

    override fun observeAutoSyncInterval(): Flow<String> = s3SyncRepository.getAutoSyncInterval()

    override fun observeSyncOnRefreshEnabled(): Flow<Boolean> = s3SyncRepository.getSyncOnRefreshEnabled()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = s3SyncRepository.observeLastSyncTimeMillis()

    override fun observeSyncState(): Flow<S3SyncState> = s3SyncRepository.syncState()
}

private class S3SyncCredentialObservationImpl(
    private val s3SyncRepository: S3SyncRepository,
) : S3SyncCredentialObservation {
    override suspend fun isAccessKeyConfigured(): Boolean = s3SyncRepository.isAccessKeyConfigured()

    override suspend fun isSecretAccessKeyConfigured(): Boolean = s3SyncRepository.isSecretAccessKeyConfigured()

    override suspend fun isSessionTokenConfigured(): Boolean = s3SyncRepository.isSessionTokenConfigured()

    override suspend fun isEncryptionPasswordConfigured(): Boolean =
        s3SyncRepository.isEncryptionPasswordConfigured()
}

private class S3SyncConnectionMutationImpl(
    private val s3SyncRepository: S3SyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
) : S3SyncConnectionMutation {
    override suspend fun updateS3SyncEnabled(enabled: Boolean) {
        syncPolicyRepository.setRemoteSyncBackend(if (enabled) SyncBackendType.S3 else SyncBackendType.NONE)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateEndpointUrl(url: String) {
        s3SyncRepository.setEndpointUrl(url)
    }

    override suspend fun updateRegion(region: String) {
        s3SyncRepository.setRegion(region)
    }

    override suspend fun updateBucket(bucket: String) {
        s3SyncRepository.setBucket(bucket)
    }

    override suspend fun updatePrefix(prefix: String) {
        s3SyncRepository.setPrefix(prefix)
    }

    override suspend fun updateLocalSyncDirectory(pathOrUri: String) {
        s3SyncRepository.setLocalSyncDirectory(pathOrUri)
    }

    override suspend fun clearLocalSyncDirectory() {
        s3SyncRepository.clearLocalSyncDirectory()
    }

    override suspend fun updatePathStyle(pathStyle: S3PathStyle) {
        s3SyncRepository.setPathStyle(pathStyle)
    }
}

private class S3SyncCredentialMutationImpl(
    private val s3SyncRepository: S3SyncRepository,
) : S3SyncCredentialMutation {
    override suspend fun updateAccessKeyId(accessKeyId: String) {
        s3SyncRepository.setAccessKeyId(accessKeyId)
    }

    override suspend fun updateSecretAccessKey(secretAccessKey: String) {
        s3SyncRepository.setSecretAccessKey(secretAccessKey)
    }

    override suspend fun updateSessionToken(sessionToken: String) {
        s3SyncRepository.setSessionToken(sessionToken)
    }

    override suspend fun updateEncryptionPassword(password: String) {
        s3SyncRepository.setEncryptionPassword(password)
    }
}

private class S3SyncBehaviorMutationImpl(
    private val s3SyncRepository: S3SyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
) : S3SyncBehaviorMutation {
    override suspend fun updateEncryptionMode(mode: S3EncryptionMode) {
        s3SyncRepository.setEncryptionMode(mode)
    }

    override suspend fun updateAutoSyncEnabled(enabled: Boolean) {
        s3SyncRepository.setAutoSyncEnabled(enabled)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateAutoSyncInterval(interval: String) {
        s3SyncRepository.setAutoSyncInterval(interval)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        s3SyncRepository.setSyncOnRefreshEnabled(enabled)
    }
}

private class S3SyncSettingsActionsImpl(
    private val s3SyncRepository: S3SyncRepository,
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
) : S3SyncSettingsActions {
    override suspend fun triggerSyncNow() {
        syncAndRebuildUseCase(forceSync = true)
    }

    override suspend fun testConnection(): S3SyncResult = s3SyncRepository.testConnection()
}
