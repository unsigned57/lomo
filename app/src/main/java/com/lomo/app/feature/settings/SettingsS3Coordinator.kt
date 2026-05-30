package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.isConfigured
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.toUnifiedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

sealed interface SettingsS3ConnectionTestState {
    data object Idle : SettingsS3ConnectionTestState

    data object Testing : SettingsS3ConnectionTestState

    data class Success(
        val message: String,
    ) : SettingsS3ConnectionTestState

    data class Error(
        val detail: String,
    ) : SettingsS3ConnectionTestState
}

class SettingsS3Coordinator(
    private val s3SyncSettingsUseCase: S3SyncSettingsUseCase,
    scope: CoroutineScope,
) : SettingsS3FeatureSupport {
    private val sharedEnabled: StateFlow<Boolean> =
        s3SyncSettingsUseCase
            .observeS3SyncEnabled()
            .settingsStateIn(scope, PreferenceDefaults.S3_SYNC_ENABLED)

    val s3EndpointUrl: StateFlow<String> =
        s3SyncSettingsUseCase
            .observeEndpointUrl()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val s3Region: StateFlow<String> =
        s3SyncSettingsUseCase
            .observeRegion()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val s3Bucket: StateFlow<String> =
        s3SyncSettingsUseCase
            .observeBucket()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val s3Prefix: StateFlow<String> =
        s3SyncSettingsUseCase
            .observePrefix()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val s3LocalSyncDirectory: StateFlow<String> =
        s3SyncSettingsUseCase
            .observeLocalSyncDirectory()
            .map { it ?: "" }
            .settingsStateIn(scope, "")

    val s3PathStyle: StateFlow<S3PathStyle> =
        s3SyncSettingsUseCase
            .observePathStyle()
            .settingsStateIn(scope, S3PathStyle.AUTO)

    val s3EncryptionMode: StateFlow<S3EncryptionMode> =
        s3SyncSettingsUseCase
            .observeEncryptionMode()
            .settingsStateIn(scope, S3EncryptionMode.NONE)

    val s3RcloneFilenameEncryption: StateFlow<S3RcloneFilenameEncryption> =
        s3SyncSettingsUseCase
            .observeRcloneFilenameEncryption()
            .settingsStateIn(scope, S3RcloneFilenameEncryption.STANDARD)

    val s3RcloneFilenameEncoding: StateFlow<S3RcloneFilenameEncoding> =
        s3SyncSettingsUseCase
            .observeRcloneFilenameEncoding()
            .settingsStateIn(scope, S3RcloneFilenameEncoding.BASE64)

    val s3RcloneDirectoryNameEncryption: StateFlow<Boolean> =
        s3SyncSettingsUseCase
            .observeRcloneDirectoryNameEncryption()
            .settingsStateIn(scope, PreferenceDefaults.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION)

    val s3RcloneDataEncryptionEnabled: StateFlow<Boolean> =
        s3SyncSettingsUseCase
            .observeRcloneDataEncryptionEnabled()
            .settingsStateIn(scope, PreferenceDefaults.S3_RCLONE_DATA_ENCRYPTION_ENABLED)

    val s3RcloneEncryptedSuffix: StateFlow<String> =
        s3SyncSettingsUseCase
            .observeRcloneEncryptedSuffix()
            .settingsStateIn(scope, PreferenceDefaults.S3_RCLONE_ENCRYPTED_SUFFIX)

    private val _accessKeyStatus = MutableStateFlow(StoredCredentialStatus.Missing)
    val accessKeyStatus: StateFlow<StoredCredentialStatus> = _accessKeyStatus.asStateFlow()

    private val _accessKeyConfigured = MutableStateFlow(false)
    val accessKeyConfigured: StateFlow<Boolean> = _accessKeyConfigured.asStateFlow()

    private val _secretAccessKeyStatus = MutableStateFlow(StoredCredentialStatus.Missing)
    val secretAccessKeyStatus: StateFlow<StoredCredentialStatus> = _secretAccessKeyStatus.asStateFlow()

    private val _secretAccessKeyConfigured = MutableStateFlow(false)
    val secretAccessKeyConfigured: StateFlow<Boolean> = _secretAccessKeyConfigured.asStateFlow()

    private val _sessionTokenStatus = MutableStateFlow(StoredCredentialStatus.Missing)
    val sessionTokenStatus: StateFlow<StoredCredentialStatus> = _sessionTokenStatus.asStateFlow()

    private val _sessionTokenConfigured = MutableStateFlow(false)
    val sessionTokenConfigured: StateFlow<Boolean> = _sessionTokenConfigured.asStateFlow()

    private val _encryptionPasswordStatus = MutableStateFlow(StoredCredentialStatus.Missing)
    val encryptionPasswordStatus: StateFlow<StoredCredentialStatus> = _encryptionPasswordStatus.asStateFlow()

    private val _encryptionPasswordConfigured = MutableStateFlow(false)
    val encryptionPasswordConfigured: StateFlow<Boolean> = _encryptionPasswordConfigured.asStateFlow()

    private val _encryptionPassword2Status = MutableStateFlow(StoredCredentialStatus.Missing)
    val encryptionPassword2Status: StateFlow<StoredCredentialStatus> = _encryptionPassword2Status.asStateFlow()

    private val _encryptionPassword2Configured = MutableStateFlow(false)
    val encryptionPassword2Configured: StateFlow<Boolean> = _encryptionPassword2Configured.asStateFlow()

    private val sharedAutoSyncEnabled: StateFlow<Boolean> =
        s3SyncSettingsUseCase
            .observeAutoSyncEnabled()
            .settingsStateIn(scope, PreferenceDefaults.S3_AUTO_SYNC_ENABLED)

    private val sharedAutoSyncInterval: StateFlow<String> =
        s3SyncSettingsUseCase
            .observeAutoSyncInterval()
            .settingsStateIn(scope, PreferenceDefaults.S3_AUTO_SYNC_INTERVAL)

    private val sharedSyncOnRefreshEnabled: StateFlow<Boolean> =
        s3SyncSettingsUseCase
            .observeSyncOnRefreshEnabled()
            .settingsStateIn(scope, PreferenceDefaults.S3_SYNC_ON_REFRESH)

    private val sharedLastSyncTime: StateFlow<Long> =
        s3SyncSettingsUseCase
            .observeLastSyncTimeMillis()
            .map { it ?: 0L }
            .settingsStateIn(scope, 0L)

    private val _connectionTestState =
        MutableStateFlow<SettingsS3ConnectionTestState>(SettingsS3ConnectionTestState.Idle)
    val connectionTestState: StateFlow<SettingsS3ConnectionTestState> = _connectionTestState.asStateFlow()

    val refreshCredentialConfigured: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to read S3 credential state") {
                setCredentialStatus(
                    targetStatus = _accessKeyStatus,
                    targetConfigured = _accessKeyConfigured,
                    status = s3SyncSettingsUseCase.getAccessKeyStatus(),
                )
                setCredentialStatus(
                    targetStatus = _secretAccessKeyStatus,
                    targetConfigured = _secretAccessKeyConfigured,
                    status = s3SyncSettingsUseCase.getSecretAccessKeyStatus(),
                )
                setCredentialStatus(
                    targetStatus = _sessionTokenStatus,
                    targetConfigured = _sessionTokenConfigured,
                    status = s3SyncSettingsUseCase.getSessionTokenStatus(),
                )
                setCredentialStatus(
                    targetStatus = _encryptionPasswordStatus,
                    targetConfigured = _encryptionPasswordConfigured,
                    status = s3SyncSettingsUseCase.getEncryptionPasswordStatus(),
                )
                setCredentialStatus(
                    targetStatus = _encryptionPassword2Status,
                    targetConfigured = _encryptionPassword2Configured,
                    status = s3SyncSettingsUseCase.getEncryptionPassword2Status(),
                )
            }
        }

    private val updateS3SyncEnabledInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update S3 sync setting") {
                s3SyncSettingsUseCase.updateS3SyncEnabled(enabled)
            }
        }

    val updateS3EndpointUrl: suspend (String) -> SettingsOperationError? =
        { url ->
            runWithError("Failed to update S3 endpoint URL") {
                s3SyncSettingsUseCase.updateEndpointUrl(url)
            }
        }

    val updateS3Region: suspend (String) -> SettingsOperationError? =
        { region ->
            runWithError("Failed to update S3 region") {
                s3SyncSettingsUseCase.updateRegion(region)
            }
        }

    val updateS3Bucket: suspend (String) -> SettingsOperationError? =
        { bucket ->
            runWithError("Failed to update S3 bucket") {
                s3SyncSettingsUseCase.updateBucket(bucket)
            }
        }

    val updateS3Prefix: suspend (String) -> SettingsOperationError? =
        { prefix ->
            runWithError("Failed to update S3 prefix") {
                s3SyncSettingsUseCase.updatePrefix(prefix)
            }
        }

    val updateS3LocalSyncDirectory: suspend (String) -> SettingsOperationError? =
        { pathOrUri ->
            runWithError("Failed to update S3 local sync directory") {
                s3SyncSettingsUseCase.updateLocalSyncDirectory(pathOrUri)
            }
        }

    val clearS3LocalSyncDirectory: suspend () -> SettingsOperationError? =
        {
            runWithError("Failed to clear S3 local sync directory") {
                s3SyncSettingsUseCase.clearLocalSyncDirectory()
            }
        }

    val updateS3AccessKeyId: suspend (String) -> SettingsOperationError? =
        { accessKeyId ->
            runWithError("Failed to update S3 access key") {
                s3SyncSettingsUseCase.updateAccessKeyId(accessKeyId)
                setCredentialStatus(
                    targetStatus = _accessKeyStatus,
                    targetConfigured = _accessKeyConfigured,
                    status = accessKeyId.toCredentialStatus(),
                )
            }
        }

    val updateS3SecretAccessKey: suspend (String) -> SettingsOperationError? =
        { secret ->
            runWithError("Failed to update S3 secret access key") {
                s3SyncSettingsUseCase.updateSecretAccessKey(secret)
                setCredentialStatus(
                    targetStatus = _secretAccessKeyStatus,
                    targetConfigured = _secretAccessKeyConfigured,
                    status = secret.toCredentialStatus(),
                )
            }
        }

    val updateS3SessionToken: suspend (String) -> SettingsOperationError? =
        { token ->
            runWithError("Failed to update S3 session token") {
                s3SyncSettingsUseCase.updateSessionToken(token)
                setCredentialStatus(
                    targetStatus = _sessionTokenStatus,
                    targetConfigured = _sessionTokenConfigured,
                    status = token.toCredentialStatus(),
                )
            }
        }

    val updateS3PathStyle: suspend (S3PathStyle) -> SettingsOperationError? =
        { pathStyle ->
            runWithError("Failed to update S3 path style") {
                s3SyncSettingsUseCase.updatePathStyle(pathStyle)
            }
        }

    val updateS3EncryptionMode: suspend (S3EncryptionMode) -> SettingsOperationError? =
        { mode ->
            runWithError("Failed to update S3 encryption mode") {
                s3SyncSettingsUseCase.updateEncryptionMode(mode)
            }
        }

    val updateS3EncryptionPassword: suspend (String) -> SettingsOperationError? =
        { password ->
            runWithError("Failed to update S3 encryption password") {
                s3SyncSettingsUseCase.updateEncryptionPassword(password)
                setCredentialStatus(
                    targetStatus = _encryptionPasswordStatus,
                    targetConfigured = _encryptionPasswordConfigured,
                    status = password.toCredentialStatus(),
                )
            }
        }

    val updateS3EncryptionPassword2: suspend (String) -> SettingsOperationError? =
        { password ->
            runWithError("Failed to update S3 secondary encryption password") {
                s3SyncSettingsUseCase.updateEncryptionPassword2(password)
                setCredentialStatus(
                    targetStatus = _encryptionPassword2Status,
                    targetConfigured = _encryptionPassword2Configured,
                    status = password.toCredentialStatus(),
                )
            }
        }

    val updateS3RcloneFilenameEncryption: suspend (S3RcloneFilenameEncryption) -> SettingsOperationError? =
        { mode ->
            runWithError("Failed to update S3 rclone filename encryption") {
                s3SyncSettingsUseCase.updateRcloneFilenameEncryption(mode)
            }
        }

    val updateS3RcloneFilenameEncoding: suspend (S3RcloneFilenameEncoding) -> SettingsOperationError? =
        { encoding ->
            runWithError("Failed to update S3 rclone filename encoding") {
                s3SyncSettingsUseCase.updateRcloneFilenameEncoding(encoding)
            }
        }

    val updateS3RcloneDirectoryNameEncryption: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update S3 directory name encryption") {
                s3SyncSettingsUseCase.updateRcloneDirectoryNameEncryption(enabled)
            }
        }

    val updateS3RcloneDataEncryptionEnabled: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update S3 data encryption") {
                s3SyncSettingsUseCase.updateRcloneDataEncryptionEnabled(enabled)
            }
        }

    val updateS3RcloneEncryptedSuffix: suspend (String) -> SettingsOperationError? =
        { suffix ->
            runWithError("Failed to update S3 encrypted suffix") {
                s3SyncSettingsUseCase.updateRcloneEncryptedSuffix(suffix)
            }
        }

    private val updateS3AutoSyncEnabledInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update S3 auto-sync setting") {
                s3SyncSettingsUseCase.updateAutoSyncEnabled(enabled)
            }
        }

    private val updateS3AutoSyncIntervalInternal: suspend (String) -> SettingsOperationError? =
        { interval ->
            runWithError("Failed to update S3 auto-sync interval") {
                s3SyncSettingsUseCase.updateAutoSyncInterval(interval)
            }
        }

    private val updateS3SyncOnRefreshInternal: suspend (Boolean) -> SettingsOperationError? =
        { enabled ->
            runWithError("Failed to update S3 sync-on-refresh setting") {
                s3SyncSettingsUseCase.updateSyncOnRefreshEnabled(enabled)
            }
        }

    private val triggerS3SyncNowInternal: suspend () -> SettingsOperationError? =
        { runWithError("Failed to run S3 sync") { s3SyncSettingsUseCase.triggerSyncNow() } }

    val testS3Connection: suspend () -> SettingsOperationError? =
        {
            runConnectionTest(
                state = _connectionTestState,
                testingState = SettingsS3ConnectionTestState.Testing,
                execute = s3SyncSettingsUseCase::testConnection,
                mapSuccess = { result ->
                    when (result) {
                        is S3SyncResult.Success -> SettingsS3ConnectionTestState.Success(result.message)
                        is S3SyncResult.Error ->
                            SettingsS3ConnectionTestState.Error(result.message.ifBlank { "S3 connection failed" })
                        S3SyncResult.NotConfigured ->
                            SettingsS3ConnectionTestState.Error("S3 sync is not configured")
                        is S3SyncResult.Conflict ->
                            SettingsS3ConnectionTestState.Error(
                                result.message.ifBlank { "S3 sync conflict detected" },
                            )
                        is S3SyncResult.Review ->
                            SettingsS3ConnectionTestState.Error(
                                result.message.ifBlank { "S3 sync review required" },
                            )
                    }
                },
                mapFailure = { throwable ->
                    SettingsS3ConnectionTestState.Error(
                        throwable.toUserMessage("Failed to test S3 connection"),
                    )
                },
            )
        }

    override fun resetConnectionTestState() {
        _connectionTestState.value = SettingsS3ConnectionTestState.Idle
    }

    private val remoteSyncSettingsCoordinator =
        RemoteSyncSettingsCoordinator(
            enabled = sharedEnabled,
            autoSyncEnabled = sharedAutoSyncEnabled,
            autoSyncInterval = sharedAutoSyncInterval,
            syncOnRefreshEnabled = sharedSyncOnRefreshEnabled,
            lastSyncTime = sharedLastSyncTime,
            rawSyncState = s3SyncSettingsUseCase.observeSyncState(),
            mapToUnifiedSyncState = { state -> state.toUnifiedState(SyncBackendType.S3) },
            updateEnabledAction = updateS3SyncEnabledInternal,
            updateAutoSyncEnabledAction = updateS3AutoSyncEnabledInternal,
            updateAutoSyncIntervalAction = updateS3AutoSyncIntervalInternal,
            updateSyncOnRefreshEnabledAction = updateS3SyncOnRefreshInternal,
            triggerSyncNowAction = triggerS3SyncNowInternal,
            testConnectionAction = testS3Connection,
        )

    val s3SyncEnabled: StateFlow<Boolean> = remoteSyncSettingsCoordinator.enabled
    val s3AutoSyncEnabled: StateFlow<Boolean> = remoteSyncSettingsCoordinator.autoSyncEnabled
    val s3AutoSyncInterval: StateFlow<String> = remoteSyncSettingsCoordinator.autoSyncInterval
    val s3SyncOnRefreshEnabled: StateFlow<Boolean> = remoteSyncSettingsCoordinator.syncOnRefreshEnabled
    val s3LastSyncTime: StateFlow<Long> = remoteSyncSettingsCoordinator.lastSyncTime
    val s3SyncState: StateFlow<UnifiedSyncState> =
        remoteSyncSettingsCoordinator.syncState.settingsStateIn(scope, UnifiedSyncState.Idle)
    val updateS3SyncEnabled: suspend (Boolean) -> SettingsOperationError? = remoteSyncSettingsCoordinator::updateEnabled
    val updateS3AutoSyncEnabled: suspend (Boolean) -> SettingsOperationError? =
        remoteSyncSettingsCoordinator::updateAutoSyncEnabled
    val updateS3AutoSyncInterval: suspend (String) -> SettingsOperationError? =
        remoteSyncSettingsCoordinator::updateAutoSyncInterval
    val updateS3SyncOnRefresh: suspend (Boolean) -> SettingsOperationError? =
        remoteSyncSettingsCoordinator::updateSyncOnRefreshEnabled
    val triggerS3SyncNow: suspend () -> SettingsOperationError? = remoteSyncSettingsCoordinator::triggerSyncNow

    override fun isValidEndpointUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
    }

    private suspend fun runWithError(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ): SettingsOperationError? = runSettingsOperation(fallbackMessage, { null }, action)

    private fun setCredentialStatus(
        targetStatus: MutableStateFlow<StoredCredentialStatus>,
        targetConfigured: MutableStateFlow<Boolean>,
        status: StoredCredentialStatus,
    ) {
        targetStatus.value = status
        targetConfigured.value = status.isConfigured
    }

    private fun String.toCredentialStatus(): StoredCredentialStatus =
        if (isBlank()) {
            StoredCredentialStatus.Missing
        } else {
            StoredCredentialStatus.Present
        }
}
