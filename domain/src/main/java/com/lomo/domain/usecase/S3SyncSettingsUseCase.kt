package com.lomo.domain.usecase

import com.lomo.domain.model.CredentialState
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.StoredCredentialStatus
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

    fun observeRcloneFilenameEncryption(): Flow<S3RcloneFilenameEncryption>

    fun observeRcloneFilenameEncoding(): Flow<S3RcloneFilenameEncoding>

    fun observeRcloneDirectoryNameEncryption(): Flow<Boolean>

    fun observeRcloneDataEncryptionEnabled(): Flow<Boolean>

    fun observeRcloneEncryptedSuffix(): Flow<String>

    fun observeAutoSyncEnabled(): Flow<Boolean>

    fun observeAutoSyncInterval(): Flow<String>

    fun observeSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeSyncState(): Flow<S3SyncState>
}

interface S3SyncCredentialObservation {
    suspend fun getAccessKeyStatus(): StoredCredentialStatus

    suspend fun getSecretAccessKeyStatus(): StoredCredentialStatus

    suspend fun getSessionTokenStatus(): StoredCredentialStatus

    suspend fun getEncryptionPasswordStatus(): StoredCredentialStatus

    suspend fun getEncryptionPassword2Status(): StoredCredentialStatus

    suspend fun getCredentialState(): CredentialState

    suspend fun isAccessKeyConfigured(): Boolean

    suspend fun isSecretAccessKeyConfigured(): Boolean

    suspend fun isSessionTokenConfigured(): Boolean

    suspend fun isEncryptionPasswordConfigured(): Boolean

    suspend fun isEncryptionPassword2Configured(): Boolean
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

    suspend fun updateEncryptionPassword2(password: String)
}

interface S3SyncBehaviorMutation {
    suspend fun updateEncryptionMode(mode: S3EncryptionMode)

    suspend fun updateRcloneFilenameEncryption(mode: S3RcloneFilenameEncryption)

    suspend fun updateRcloneFilenameEncoding(encoding: S3RcloneFilenameEncoding)

    suspend fun updateRcloneDirectoryNameEncryption(enabled: Boolean)

    suspend fun updateRcloneDataEncryptionEnabled(enabled: Boolean)

    suspend fun updateRcloneEncryptedSuffix(suffix: String)

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
    private val shared =
        RemoteSyncSharedStateObservationImpl(
            enabled = s3SyncRepository::isS3SyncEnabled,
            autoSyncEnabled = s3SyncRepository::getAutoSyncEnabled,
            autoSyncInterval = s3SyncRepository::getAutoSyncInterval,
            syncOnRefreshEnabled = s3SyncRepository::getSyncOnRefreshEnabled,
            lastSyncTimeMillis = s3SyncRepository::observeLastSyncTimeMillis,
            syncState = s3SyncRepository::syncState,
        )

    override fun observeS3SyncEnabled(): Flow<Boolean> = shared.observeSyncEnabled()

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
    private val shared =
        RemoteSyncSharedStateObservationImpl(
            enabled = s3SyncRepository::isS3SyncEnabled,
            autoSyncEnabled = s3SyncRepository::getAutoSyncEnabled,
            autoSyncInterval = s3SyncRepository::getAutoSyncInterval,
            syncOnRefreshEnabled = s3SyncRepository::getSyncOnRefreshEnabled,
            lastSyncTimeMillis = s3SyncRepository::observeLastSyncTimeMillis,
            syncState = s3SyncRepository::syncState,
        )

    override fun observeEncryptionMode(): Flow<S3EncryptionMode> = s3SyncRepository.getEncryptionMode()

    override fun observeRcloneFilenameEncryption(): Flow<S3RcloneFilenameEncryption> =
        s3SyncRepository.getRcloneFilenameEncryption()

    override fun observeRcloneFilenameEncoding(): Flow<S3RcloneFilenameEncoding> =
        s3SyncRepository.getRcloneFilenameEncoding()

    override fun observeRcloneDirectoryNameEncryption(): Flow<Boolean> =
        s3SyncRepository.getRcloneDirectoryNameEncryption()

    override fun observeRcloneDataEncryptionEnabled(): Flow<Boolean> =
        s3SyncRepository.getRcloneDataEncryptionEnabled()

    override fun observeRcloneEncryptedSuffix(): Flow<String> =
        s3SyncRepository.getRcloneEncryptedSuffix()

    override fun observeAutoSyncEnabled(): Flow<Boolean> = shared.observeAutoSyncEnabled()

    override fun observeAutoSyncInterval(): Flow<String> = shared.observeAutoSyncInterval()

    override fun observeSyncOnRefreshEnabled(): Flow<Boolean> = shared.observeSyncOnRefreshEnabled()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = shared.observeLastSyncTimeMillis()

    override fun observeSyncState(): Flow<S3SyncState> = shared.observeSyncState()
}

private class S3SyncCredentialObservationImpl(
    private val s3SyncRepository: S3SyncRepository,
) : S3SyncCredentialObservation {
    override suspend fun getAccessKeyStatus(): StoredCredentialStatus = s3SyncRepository.getAccessKeyStatus()

    override suspend fun getSecretAccessKeyStatus(): StoredCredentialStatus =
        s3SyncRepository.getSecretAccessKeyStatus()

    override suspend fun getSessionTokenStatus(): StoredCredentialStatus = s3SyncRepository.getSessionTokenStatus()

    override suspend fun getEncryptionPasswordStatus(): StoredCredentialStatus =
        s3SyncRepository.getEncryptionPasswordStatus()

    override suspend fun getEncryptionPassword2Status(): StoredCredentialStatus =
        s3SyncRepository.getEncryptionPassword2Status()

    override suspend fun getCredentialState(): CredentialState = s3SyncRepository.getCredentialState()

    override suspend fun isAccessKeyConfigured(): Boolean = s3SyncRepository.isAccessKeyConfigured()

    override suspend fun isSecretAccessKeyConfigured(): Boolean = s3SyncRepository.isSecretAccessKeyConfigured()

    override suspend fun isSessionTokenConfigured(): Boolean = s3SyncRepository.isSessionTokenConfigured()

    override suspend fun isEncryptionPasswordConfigured(): Boolean =
        s3SyncRepository.isEncryptionPasswordConfigured()

    override suspend fun isEncryptionPassword2Configured(): Boolean =
        s3SyncRepository.isEncryptionPassword2Configured()
}

private class S3SyncConnectionMutationImpl(
    private val s3SyncRepository: S3SyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
) : S3SyncConnectionMutation {
    private val shared =
        RemoteSyncSharedMutationImpl(
            backendType = SyncBackendType.S3,
            syncPolicyRepository = syncPolicyRepository,
            autoSyncEnabledUpdater = s3SyncRepository::setAutoSyncEnabled,
            autoSyncIntervalUpdater = s3SyncRepository::setAutoSyncInterval,
            syncOnRefreshUpdater = s3SyncRepository::setSyncOnRefreshEnabled,
        )

    override suspend fun updateS3SyncEnabled(enabled: Boolean) {
        shared.updateSyncEnabled(enabled)
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

    override suspend fun updateEncryptionPassword2(password: String) {
        s3SyncRepository.setEncryptionPassword2(password)
    }
}

private class S3SyncBehaviorMutationImpl(
    private val s3SyncRepository: S3SyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
) : S3SyncBehaviorMutation {
    private val shared =
        RemoteSyncSharedMutationImpl(
            backendType = SyncBackendType.S3,
            syncPolicyRepository = syncPolicyRepository,
            autoSyncEnabledUpdater = s3SyncRepository::setAutoSyncEnabled,
            autoSyncIntervalUpdater = s3SyncRepository::setAutoSyncInterval,
            syncOnRefreshUpdater = s3SyncRepository::setSyncOnRefreshEnabled,
        )

    override suspend fun updateEncryptionMode(mode: S3EncryptionMode) {
        s3SyncRepository.setEncryptionMode(mode)
    }

    override suspend fun updateRcloneFilenameEncryption(mode: S3RcloneFilenameEncryption) {
        s3SyncRepository.setRcloneFilenameEncryption(mode)
    }

    override suspend fun updateRcloneFilenameEncoding(encoding: S3RcloneFilenameEncoding) {
        s3SyncRepository.setRcloneFilenameEncoding(encoding)
    }

    override suspend fun updateRcloneDirectoryNameEncryption(enabled: Boolean) {
        s3SyncRepository.setRcloneDirectoryNameEncryption(enabled)
    }

    override suspend fun updateRcloneDataEncryptionEnabled(enabled: Boolean) {
        s3SyncRepository.setRcloneDataEncryptionEnabled(enabled)
    }

    override suspend fun updateRcloneEncryptedSuffix(suffix: String) {
        s3SyncRepository.setRcloneEncryptedSuffix(suffix)
    }

    override suspend fun updateAutoSyncEnabled(enabled: Boolean) {
        shared.updateAutoSyncEnabled(enabled)
    }

    override suspend fun updateAutoSyncInterval(interval: String) {
        shared.updateAutoSyncInterval(interval)
    }

    override suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        shared.updateSyncOnRefreshEnabled(enabled)
    }
}

private class S3SyncSettingsActionsImpl(
    private val s3SyncRepository: S3SyncRepository,
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
) : S3SyncSettingsActions {
    private val shared =
        RemoteSyncSharedActionsImpl(
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            connectionTester = s3SyncRepository::testConnection,
        )

    override suspend fun triggerSyncNow() {
        shared.triggerSyncNow()
    }

    override suspend fun testConnection(): S3SyncResult = shared.testConnection()
}
