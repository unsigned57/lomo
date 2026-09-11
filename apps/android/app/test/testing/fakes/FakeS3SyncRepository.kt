package com.lomo.app.testing.fakes

import com.lomo.domain.repository.S3SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeS3SyncRepository : S3SyncRepository {
    private val _isS3SyncEnabled = MutableStateFlow(false)
    private val _syncOnRefreshEnabled = MutableStateFlow(false)

    fun updateS3SyncEnabled(enabled: Boolean) {
        _isS3SyncEnabled.value = enabled
    }

    fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        _syncOnRefreshEnabled.value = enabled
    }

    override fun isS3SyncEnabled(): Flow<Boolean> = _isS3SyncEnabled.asStateFlow()
    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = _syncOnRefreshEnabled.asStateFlow()

    override suspend fun setS3SyncEnabled(enabled: Boolean) { _isS3SyncEnabled.value = enabled }
    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) { _syncOnRefreshEnabled.value = enabled }

    override fun getEndpointUrl(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setEndpointUrl(url: String) {}
    override fun getRegion(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setRegion(region: String) {}
    override fun getBucket(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setBucket(bucket: String) {}
    override fun getPrefix(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setPrefix(prefix: String) {}
    override fun getLocalSyncDirectory(): Flow<String?> = MutableStateFlow(null)
    override suspend fun setLocalSyncDirectory(pathOrUri: String) {}
    override suspend fun clearLocalSyncDirectory() {}
    override fun getPathStyle(): Flow<com.lomo.domain.model.S3PathStyle> = MutableStateFlow(com.lomo.domain.model.S3PathStyle.AUTO)
    override suspend fun setPathStyle(pathStyle: com.lomo.domain.model.S3PathStyle) {}

    override fun getEncryptionMode(): Flow<com.lomo.domain.model.S3EncryptionMode> = MutableStateFlow(com.lomo.domain.model.S3EncryptionMode.NONE)
    override suspend fun setEncryptionMode(mode: com.lomo.domain.model.S3EncryptionMode) {}
    override fun getRcloneFilenameEncryption(): Flow<com.lomo.domain.model.S3RcloneFilenameEncryption> = MutableStateFlow(com.lomo.domain.model.S3RcloneFilenameEncryption.OFF)
    override suspend fun setRcloneFilenameEncryption(mode: com.lomo.domain.model.S3RcloneFilenameEncryption) {}
    override fun getRcloneFilenameEncoding(): Flow<com.lomo.domain.model.S3RcloneFilenameEncoding> = MutableStateFlow(com.lomo.domain.model.S3RcloneFilenameEncoding.BASE32)
    override suspend fun setRcloneFilenameEncoding(encoding: com.lomo.domain.model.S3RcloneFilenameEncoding) {}
    override fun getRcloneDirectoryNameEncryption(): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setRcloneDirectoryNameEncryption(enabled: Boolean) {}
    override fun getRcloneDataEncryptionEnabled(): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setRcloneDataEncryptionEnabled(enabled: Boolean) {}
    override fun getRcloneEncryptedSuffix(): Flow<String> = MutableStateFlow(".bin")
    override suspend fun setRcloneEncryptedSuffix(suffix: String) {}
    override fun getAutoSyncEnabled(): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setAutoSyncEnabled(enabled: Boolean) {}
    override fun getAutoSyncInterval(): Flow<String> = MutableStateFlow("15m")
    override suspend fun setAutoSyncInterval(interval: String) {}
    override fun observeLastSyncTimeMillis(): Flow<Long?> = MutableStateFlow(null)

    override suspend fun setAccessKeyId(accessKeyId: String) {}
    override suspend fun setSecretAccessKey(secretAccessKey: String) {}
    override suspend fun setSessionToken(sessionToken: String) {}
    override suspend fun setEncryptionPassword(password: String) {}
    override suspend fun setEncryptionPassword2(password: String) {}
    override suspend fun isAccessKeyConfigured(): Boolean = false
    override suspend fun isSecretAccessKeyConfigured(): Boolean = false
    override suspend fun isSessionTokenConfigured(): Boolean = false
    override suspend fun isEncryptionPasswordConfigured(): Boolean = false
    override suspend fun isEncryptionPassword2Configured(): Boolean = false

    override suspend fun sync(): com.lomo.domain.model.S3SyncResult = com.lomo.domain.model.S3SyncResult.Success("")
    override suspend fun getStatus(): com.lomo.domain.model.S3SyncStatus = com.lomo.domain.model.S3SyncStatus(0, 0, 0, null)
    override suspend fun testConnection(): com.lomo.domain.model.S3SyncResult = com.lomo.domain.model.S3SyncResult.Success("")

    override suspend fun resolveConflicts(
        resolution: com.lomo.domain.model.SyncConflictResolution,
        conflictSet: com.lomo.domain.model.SyncConflictSet,
    ): com.lomo.domain.model.S3SyncResult = com.lomo.domain.model.S3SyncResult.Success("")

    override suspend fun resolveReview(
        resolution: com.lomo.domain.model.SyncReviewResolution,
        review: com.lomo.domain.model.SyncReviewSession,
    ): com.lomo.domain.model.S3SyncResult = com.lomo.domain.model.S3SyncResult.Success("")

    override fun syncState(): Flow<com.lomo.domain.model.S3SyncState> = MutableStateFlow(com.lomo.domain.model.S3SyncState.Idle)
}
