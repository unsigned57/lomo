package com.lomo.domain.testing.fakes

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3RemoteIndexState
import com.lomo.domain.model.S3SyncScanPolicy
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeGitSyncRepository : GitSyncRepository {
    private val enabled = MutableStateFlow(false)
    private val remoteUrl = MutableStateFlow<String?>(null)
    private val authorName = MutableStateFlow("")
    private val authorEmail = MutableStateFlow("")
    private val autoSyncEnabled = MutableStateFlow(false)
    private val autoSyncInterval = MutableStateFlow("30m")
    private val syncOnRefreshEnabled = MutableStateFlow(false)
    private val lastSyncTimeMillis = MutableStateFlow<Long?>(null)
    private val syncState = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)

    val remoteUrlWrites = mutableListOf<String>()
    val tokenWrites = mutableListOf<String>()
    val authorInfoWrites = mutableListOf<Pair<String, String>>()
    val autoSyncEnabledWrites = mutableListOf<Boolean>()
    val autoSyncIntervalWrites = mutableListOf<String>()
    val syncOnRefreshEnabledWrites = mutableListOf<Boolean>()
    val resolveRequests = mutableListOf<Pair<SyncConflictResolution, SyncConflictSet>>()

    var token: String? = null
    var syncCallCount = 0
        private set
    var testConnectionCallCount = 0
        private set
    var resetRepositoryCallCount = 0
        private set
    var resetLocalBranchToRemoteCallCount = 0
        private set
    var forcePushLocalToRemoteCallCount = 0
        private set
    var nextSyncResult: GitSyncResult = GitSyncResult.Success("synced")
    var syncFailure: Exception? = null
    var nextTestConnectionResult: GitSyncResult = GitSyncResult.Success("connected")
    var nextResetRepositoryResult: GitSyncResult = GitSyncResult.Success("reset")
    var nextResetLocalBranchToRemoteResult: GitSyncResult = GitSyncResult.Success("remote reset")
    var resetLocalBranchFailure: Exception? = null
    var nextForcePushLocalToRemoteResult: GitSyncResult = GitSyncResult.Success("local pushed")
    var forcePushLocalFailure: Exception? = null
    var nextResolveConflictsResult: GitSyncResult = GitSyncResult.Success("resolved")

    fun setEnabled(value: Boolean) {
        enabled.value = value
    }

    fun setRemoteUrlValue(value: String?) {
        remoteUrl.value = value
    }

    fun setAuthorNameValue(value: String) {
        authorName.value = value
    }

    fun setAuthorEmailValue(value: String) {
        authorEmail.value = value
    }

    fun setAutoSyncEnabledValue(value: Boolean) {
        autoSyncEnabled.value = value
    }

    fun setAutoSyncIntervalValue(value: String) {
        autoSyncInterval.value = value
    }

    fun setSyncOnRefreshEnabledValue(value: Boolean) {
        syncOnRefreshEnabled.value = value
    }

    fun setLastSyncTimeMillis(value: Long?) {
        lastSyncTimeMillis.value = value
    }

    fun setSyncState(value: UnifiedSyncState) {
        syncState.value = value
    }

    override fun isGitSyncEnabled(): Flow<Boolean> = enabled.asStateFlow()

    override fun getRemoteUrl(): Flow<String?> = remoteUrl.asStateFlow()

    override fun getAutoSyncEnabled(): Flow<Boolean> = autoSyncEnabled.asStateFlow()

    override fun getAutoSyncInterval(): Flow<String> = autoSyncInterval.asStateFlow()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = lastSyncTimeMillis.asStateFlow()

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = syncOnRefreshEnabled.asStateFlow()

    override suspend fun setGitSyncEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setRemoteUrl(url: String) {
        remoteUrlWrites += url
        remoteUrl.value = url
    }

    override suspend fun setToken(token: String) {
        tokenWrites += token
        this.token = token
    }

    override suspend fun getToken(): String? = token

    override suspend fun setAuthorInfo(
        name: String,
        email: String,
    ) {
        authorInfoWrites += name to email
        authorName.value = name
        authorEmail.value = email
    }

    override fun getAuthorName(): Flow<String> = authorName.asStateFlow()

    override fun getAuthorEmail(): Flow<String> = authorEmail.asStateFlow()

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        autoSyncEnabledWrites += enabled
        autoSyncEnabled.value = enabled
    }

    override suspend fun setAutoSyncInterval(interval: String) {
        autoSyncIntervalWrites += interval
        autoSyncInterval.value = interval
    }

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
        syncOnRefreshEnabledWrites += enabled
        syncOnRefreshEnabled.value = enabled
    }

    override suspend fun initOrClone(): GitSyncResult = GitSyncResult.Success("initialized")

    override suspend fun sync(): GitSyncResult {
        syncCallCount += 1
        syncFailure?.let { throw it }
        return nextSyncResult
    }

    override suspend fun getStatus(): GitSyncStatus =
        GitSyncStatus(hasLocalChanges = false, aheadCount = 0, behindCount = 0, lastSyncTime = null)

    override suspend fun testConnection(): GitSyncResult {
        testConnectionCallCount += 1
        return nextTestConnectionResult
    }

    override suspend fun resetRepository(): GitSyncResult {
        resetRepositoryCallCount += 1
        return nextResetRepositoryResult
    }

    override suspend fun resetLocalBranchToRemote(): GitSyncResult {
        resetLocalBranchToRemoteCallCount += 1
        resetLocalBranchFailure?.let { throw it }
        return nextResetLocalBranchToRemoteResult
    }

    override suspend fun forcePushLocalToRemote(): GitSyncResult {
        forcePushLocalToRemoteCallCount += 1
        forcePushLocalFailure?.let { throw it }
        return nextForcePushLocalToRemoteResult
    }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): GitSyncResult {
        resolveRequests += resolution to conflictSet
        return nextResolveConflictsResult
    }

    override fun syncState(): Flow<UnifiedSyncState> = syncState.asStateFlow()
}

class FakeWebDavSyncRepository : WebDavSyncRepository {
    private val enabled = MutableStateFlow(false)
    private val provider = MutableStateFlow(WebDavProvider.CUSTOM)
    private val baseUrl = MutableStateFlow<String?>(null)
    private val endpointUrl = MutableStateFlow<String?>(null)
    private val username = MutableStateFlow<String?>(null)
    private val autoSyncEnabled = MutableStateFlow(false)
    private val autoSyncInterval = MutableStateFlow("30m")
    private val syncOnRefreshEnabled = MutableStateFlow(false)
    private val lastSyncTimeMillis = MutableStateFlow<Long?>(null)
    private val syncState = MutableStateFlow<WebDavSyncState>(WebDavSyncState.Idle)

    val providerWrites = mutableListOf<WebDavProvider>()
    val baseUrlWrites = mutableListOf<String>()
    val endpointUrlWrites = mutableListOf<String>()
    val usernameWrites = mutableListOf<String>()
    val passwordWrites = mutableListOf<String>()
    val autoSyncEnabledWrites = mutableListOf<Boolean>()
    val autoSyncIntervalWrites = mutableListOf<String>()
    val syncOnRefreshEnabledWrites = mutableListOf<Boolean>()
    val resolveRequests = mutableListOf<Pair<SyncConflictResolution, SyncConflictSet>>()

    var passwordConfigured = false
    var syncCallCount = 0
        private set
    var nextSyncResult: WebDavSyncResult = WebDavSyncResult.Success("synced")
    var nextTestConnectionResult: WebDavSyncResult = WebDavSyncResult.Success("connected")
    var testConnectionCallCount = 0
        private set
    var nextResolveConflictsResult: WebDavSyncResult = WebDavSyncResult.Success("resolved")

    fun setEnabled(value: Boolean) {
        enabled.value = value
    }

    fun setAutoSyncEnabledValue(value: Boolean) {
        autoSyncEnabled.value = value
    }

    fun setAutoSyncIntervalValue(value: String) {
        autoSyncInterval.value = value
    }

    fun setSyncOnRefreshEnabledValue(value: Boolean) {
        syncOnRefreshEnabled.value = value
    }

    fun setLastSyncTimeMillis(value: Long?) {
        lastSyncTimeMillis.value = value
    }

    fun setSyncState(value: WebDavSyncState) {
        syncState.value = value
    }

    override fun isWebDavSyncEnabled(): Flow<Boolean> = enabled.asStateFlow()

    override fun getProvider(): Flow<WebDavProvider> = provider.asStateFlow()

    override fun getBaseUrl(): Flow<String?> = baseUrl.asStateFlow()

    override fun getEndpointUrl(): Flow<String?> = endpointUrl.asStateFlow()

    override fun getUsername(): Flow<String?> = username.asStateFlow()

    override fun getAutoSyncEnabled(): Flow<Boolean> = autoSyncEnabled.asStateFlow()

    override fun getAutoSyncInterval(): Flow<String> = autoSyncInterval.asStateFlow()

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = syncOnRefreshEnabled.asStateFlow()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = lastSyncTimeMillis.asStateFlow()

    override suspend fun setWebDavSyncEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setProvider(provider: WebDavProvider) {
        providerWrites += provider
        this.provider.value = provider
    }

    override suspend fun setBaseUrl(url: String) {
        baseUrlWrites += url
        baseUrl.value = url
    }

    override suspend fun setEndpointUrl(url: String) {
        endpointUrlWrites += url
        endpointUrl.value = url
    }

    override suspend fun setUsername(username: String) {
        usernameWrites += username
        this.username.value = username
    }

    override suspend fun setPassword(password: String) {
        passwordWrites += password
        passwordConfigured = true
    }

    override suspend fun isPasswordConfigured(): Boolean = passwordConfigured

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        autoSyncEnabledWrites += enabled
        autoSyncEnabled.value = enabled
    }

    override suspend fun setAutoSyncInterval(interval: String) {
        autoSyncIntervalWrites += interval
        autoSyncInterval.value = interval
    }

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
        syncOnRefreshEnabledWrites += enabled
        syncOnRefreshEnabled.value = enabled
    }

    override suspend fun sync(): WebDavSyncResult {
        syncCallCount += 1
        return nextSyncResult
    }

    override suspend fun getStatus(): WebDavSyncStatus =
        WebDavSyncStatus(remoteFileCount = 0, localFileCount = 0, pendingChanges = 0, lastSyncTime = null)

    override suspend fun testConnection(): WebDavSyncResult {
        testConnectionCallCount += 1
        return nextTestConnectionResult
    }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): WebDavSyncResult {
        resolveRequests += resolution to conflictSet
        return nextResolveConflictsResult
    }

    override fun syncState(): Flow<WebDavSyncState> = syncState.asStateFlow()
}

class FakeS3SyncRepository : S3SyncRepository {
    private val enabled = MutableStateFlow(false)
    private val endpointUrl = MutableStateFlow<String?>(null)
    private val region = MutableStateFlow<String?>(null)
    private val bucket = MutableStateFlow<String?>(null)
    private val prefix = MutableStateFlow<String?>(null)
    private val localSyncDirectory = MutableStateFlow<String?>(null)
    private val pathStyle = MutableStateFlow(S3PathStyle.AUTO)
    private val encryptionMode = MutableStateFlow(S3EncryptionMode.NONE)
    private val filenameEncryption = MutableStateFlow(S3RcloneFilenameEncryption.STANDARD)
    private val filenameEncoding = MutableStateFlow(S3RcloneFilenameEncoding.BASE64)
    private val directoryNameEncryption = MutableStateFlow(true)
    private val dataEncryptionEnabled = MutableStateFlow(true)
    private val encryptedSuffix = MutableStateFlow(".bin")
    private val autoSyncEnabled = MutableStateFlow(false)
    private val autoSyncInterval = MutableStateFlow("30m")
    private val syncOnRefreshEnabled = MutableStateFlow(false)
    private val lastSyncTimeMillis = MutableStateFlow<Long?>(null)
    private val syncState = MutableStateFlow<S3SyncState>(S3SyncState.Idle)

    val endpointUrlWrites = mutableListOf<String>()
    val regionWrites = mutableListOf<String>()
    val bucketWrites = mutableListOf<String>()
    val prefixWrites = mutableListOf<String>()
    val localSyncDirectoryWrites = mutableListOf<String>()
    val pathStyleWrites = mutableListOf<S3PathStyle>()
    val accessKeyWrites = mutableListOf<String>()
    val secretAccessKeyWrites = mutableListOf<String>()
    val sessionTokenWrites = mutableListOf<String>()
    val encryptionPasswordWrites = mutableListOf<String>()
    val encryptionPassword2Writes = mutableListOf<String>()
    val encryptionModeWrites = mutableListOf<S3EncryptionMode>()
    val filenameEncryptionWrites = mutableListOf<S3RcloneFilenameEncryption>()
    val filenameEncodingWrites = mutableListOf<S3RcloneFilenameEncoding>()
    val directoryNameEncryptionWrites = mutableListOf<Boolean>()
    val dataEncryptionEnabledWrites = mutableListOf<Boolean>()
    val encryptedSuffixWrites = mutableListOf<String>()
    val autoSyncEnabledWrites = mutableListOf<Boolean>()
    val autoSyncIntervalWrites = mutableListOf<String>()
    val syncOnRefreshEnabledWrites = mutableListOf<Boolean>()
    val syncPolicies = mutableListOf<S3SyncScanPolicy>()
    val resolveRequests = mutableListOf<Pair<SyncConflictResolution, SyncConflictSet>>()

    var clearLocalSyncDirectoryCallCount = 0
        private set
    var accessKeyConfigured = false
    var secretAccessKeyConfigured = false
    var sessionTokenConfigured = false
    var encryptionPasswordConfigured = false
    var encryptionPassword2Configured = false
    var nextSyncResult: S3SyncResult = S3SyncResult.Success("synced")
    var nextTestConnectionResult: S3SyncResult = S3SyncResult.Success("connected")
    var nextResolveConflictsResult: S3SyncResult = S3SyncResult.Success("resolved")
    var testConnectionCallCount = 0
        private set

    fun setEnabled(value: Boolean) {
        enabled.value = value
    }

    fun setAutoSyncEnabledValue(value: Boolean) {
        autoSyncEnabled.value = value
    }

    fun setAutoSyncIntervalValue(value: String) {
        autoSyncInterval.value = value
    }

    fun setSyncOnRefreshEnabledValue(value: Boolean) {
        syncOnRefreshEnabled.value = value
    }

    fun setLastSyncTimeMillis(value: Long?) {
        lastSyncTimeMillis.value = value
    }

    fun setSyncState(value: S3SyncState) {
        syncState.value = value
    }

    override fun isS3SyncEnabled(): Flow<Boolean> = enabled.asStateFlow()

    override fun getEndpointUrl(): Flow<String?> = endpointUrl.asStateFlow()

    override fun getRegion(): Flow<String?> = region.asStateFlow()

    override fun getBucket(): Flow<String?> = bucket.asStateFlow()

    override fun getPrefix(): Flow<String?> = prefix.asStateFlow()

    override fun getLocalSyncDirectory(): Flow<String?> = localSyncDirectory.asStateFlow()

    override fun getPathStyle(): Flow<S3PathStyle> = pathStyle.asStateFlow()

    override fun getEncryptionMode(): Flow<S3EncryptionMode> = encryptionMode.asStateFlow()

    override fun getRcloneFilenameEncryption(): Flow<S3RcloneFilenameEncryption> = filenameEncryption.asStateFlow()

    override fun getRcloneFilenameEncoding(): Flow<S3RcloneFilenameEncoding> = filenameEncoding.asStateFlow()

    override fun getRcloneDirectoryNameEncryption(): Flow<Boolean> = directoryNameEncryption.asStateFlow()

    override fun getRcloneDataEncryptionEnabled(): Flow<Boolean> = dataEncryptionEnabled.asStateFlow()

    override fun getRcloneEncryptedSuffix(): Flow<String> = encryptedSuffix.asStateFlow()

    override fun getAutoSyncEnabled(): Flow<Boolean> = autoSyncEnabled.asStateFlow()

    override fun getAutoSyncInterval(): Flow<String> = autoSyncInterval.asStateFlow()

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = syncOnRefreshEnabled.asStateFlow()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = lastSyncTimeMillis.asStateFlow()

    override suspend fun setS3SyncEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setEndpointUrl(url: String) {
        endpointUrlWrites += url
        endpointUrl.value = url
    }

    override suspend fun setRegion(region: String) {
        regionWrites += region
        this.region.value = region
    }

    override suspend fun setBucket(bucket: String) {
        bucketWrites += bucket
        this.bucket.value = bucket
    }

    override suspend fun setPrefix(prefix: String) {
        prefixWrites += prefix
        this.prefix.value = prefix
    }

    override suspend fun setLocalSyncDirectory(pathOrUri: String) {
        localSyncDirectoryWrites += pathOrUri
        localSyncDirectory.value = pathOrUri
    }

    override suspend fun clearLocalSyncDirectory() {
        clearLocalSyncDirectoryCallCount += 1
        localSyncDirectory.value = null
    }

    override suspend fun setPathStyle(pathStyle: S3PathStyle) {
        pathStyleWrites += pathStyle
        this.pathStyle.value = pathStyle
    }

    override suspend fun setAccessKeyId(accessKeyId: String) {
        accessKeyWrites += accessKeyId
        accessKeyConfigured = accessKeyId.isNotEmpty()
    }

    override suspend fun setSecretAccessKey(secretAccessKey: String) {
        secretAccessKeyWrites += secretAccessKey
        secretAccessKeyConfigured = secretAccessKey.isNotEmpty()
    }

    override suspend fun setSessionToken(sessionToken: String) {
        sessionTokenWrites += sessionToken
        sessionTokenConfigured = sessionToken.isNotEmpty()
    }

    override suspend fun setEncryptionPassword(password: String) {
        encryptionPasswordWrites += password
        encryptionPasswordConfigured = password.isNotEmpty()
    }

    override suspend fun setEncryptionPassword2(password: String) {
        encryptionPassword2Writes += password
        encryptionPassword2Configured = password.isNotEmpty()
    }

    override suspend fun isAccessKeyConfigured(): Boolean = accessKeyConfigured

    override suspend fun isSecretAccessKeyConfigured(): Boolean = secretAccessKeyConfigured

    override suspend fun isSessionTokenConfigured(): Boolean = sessionTokenConfigured

    override suspend fun isEncryptionPasswordConfigured(): Boolean = encryptionPasswordConfigured

    override suspend fun isEncryptionPassword2Configured(): Boolean = encryptionPassword2Configured

    override suspend fun setEncryptionMode(mode: S3EncryptionMode) {
        encryptionModeWrites += mode
        encryptionMode.value = mode
    }

    override suspend fun setRcloneFilenameEncryption(mode: S3RcloneFilenameEncryption) {
        filenameEncryptionWrites += mode
        filenameEncryption.value = mode
    }

    override suspend fun setRcloneFilenameEncoding(encoding: S3RcloneFilenameEncoding) {
        filenameEncodingWrites += encoding
        filenameEncoding.value = encoding
    }

    override suspend fun setRcloneDirectoryNameEncryption(enabled: Boolean) {
        directoryNameEncryptionWrites += enabled
        directoryNameEncryption.value = enabled
    }

    override suspend fun setRcloneDataEncryptionEnabled(enabled: Boolean) {
        dataEncryptionEnabledWrites += enabled
        dataEncryptionEnabled.value = enabled
    }

    override suspend fun setRcloneEncryptedSuffix(suffix: String) {
        encryptedSuffixWrites += suffix
        encryptedSuffix.value = suffix
    }

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        autoSyncEnabledWrites += enabled
        autoSyncEnabled.value = enabled
    }

    override suspend fun setAutoSyncInterval(interval: String) {
        autoSyncIntervalWrites += interval
        autoSyncInterval.value = interval
    }

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
        syncOnRefreshEnabledWrites += enabled
        syncOnRefreshEnabled.value = enabled
    }

    override suspend fun sync(policy: S3SyncScanPolicy): S3SyncResult {
        syncPolicies += policy
        return nextSyncResult
    }

    override suspend fun getStatus(): S3SyncStatus =
        S3SyncStatus(remoteFileCount = 0, localFileCount = 0, pendingChanges = 0, lastSyncTime = null)

    override suspend fun getRemoteIndexState(): S3RemoteIndexState? = null

    override suspend fun testConnection(): S3SyncResult {
        testConnectionCallCount += 1
        return nextTestConnectionResult
    }

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): S3SyncResult {
        resolveRequests += resolution to conflictSet
        return nextResolveConflictsResult
    }

    override fun syncState(): Flow<S3SyncState> = syncState.asStateFlow()
}
