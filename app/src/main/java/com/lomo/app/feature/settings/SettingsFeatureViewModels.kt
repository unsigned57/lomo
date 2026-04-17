package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDownloadManager
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface SettingsLanShareFeatureActions {
    val updateLanShareE2eEnabled: (Boolean) -> Unit
    val updateLanSharePairingCode: (String) -> Unit
    val clearLanSharePairingCode: () -> Unit
    val updateLanShareDeviceName: (String) -> Unit
}

interface SettingsLanShareFeatureSupport {
    fun clearPairingCodeError()
}

interface SettingsGitFeatureActions {
    val updateGitSyncEnabled: (Boolean) -> Unit
    val updateGitRemoteUrl: (String) -> Unit
    val updateGitPat: (String) -> Unit
    val updateGitAuthorName: (String) -> Unit
    val updateGitAuthorEmail: (String) -> Unit
    val updateGitAutoSyncEnabled: (Boolean) -> Unit
    val updateGitAutoSyncInterval: (String) -> Unit
    val updateGitSyncOnRefresh: (Boolean) -> Unit
    val triggerGitSyncNow: () -> Unit
    val resolveGitConflictUsingRemote: () -> Unit
    val resolveGitConflictUsingLocal: () -> Unit
    val testGitConnection: () -> Unit
    val resetGitRepository: () -> Unit
}

interface SettingsGitFeatureSupport {
    val isValidGitRemoteUrl: (String) -> Boolean
    val shouldShowGitConflictDialog: (GitSyncErrorCode) -> Boolean
    val resetConnectionTestState: () -> Unit
}

interface SettingsWebDavFeatureActions {
    val updateWebDavSyncEnabled: (Boolean) -> Unit
    val updateWebDavProvider: (WebDavProvider) -> Unit
    val updateWebDavBaseUrl: (String) -> Unit
    val updateWebDavEndpointUrl: (String) -> Unit
    val updateWebDavUsername: (String) -> Unit
    val updateWebDavPassword: (String) -> Unit
    val updateWebDavAutoSyncEnabled: (Boolean) -> Unit
    val updateWebDavAutoSyncInterval: (String) -> Unit
    val updateWebDavSyncOnRefresh: (Boolean) -> Unit
    val triggerWebDavSyncNow: () -> Unit
    val testWebDavConnection: () -> Unit
}

interface SettingsWebDavFeatureSupport {
    fun resetConnectionTestState()
    fun isValidWebDavUrl(url: String): Boolean
}

interface SettingsS3FeatureActions {
    val updateS3SyncEnabled: (Boolean) -> Unit
    val updateS3EndpointUrl: (String) -> Unit
    val updateS3Region: (String) -> Unit
    val updateS3Bucket: (String) -> Unit
    val updateS3Prefix: (String) -> Unit
    val updateS3LocalSyncDirectory: (String) -> Unit
    val clearS3LocalSyncDirectory: () -> Unit
    val updateS3AccessKeyId: (String) -> Unit
    val updateS3SecretAccessKey: (String) -> Unit
    val updateS3SessionToken: (String) -> Unit
    val updateS3PathStyle: (S3PathStyle) -> Unit
    val updateS3EncryptionMode: (S3EncryptionMode) -> Unit
    val updateS3EncryptionPassword: (String) -> Unit
    val updateS3EncryptionPassword2: (String) -> Unit
    val updateS3RcloneFilenameEncryption: (S3RcloneFilenameEncryption) -> Unit
    val updateS3RcloneFilenameEncoding: (S3RcloneFilenameEncoding) -> Unit
    val updateS3RcloneDirectoryNameEncryption: (Boolean) -> Unit
    val updateS3RcloneDataEncryptionEnabled: (Boolean) -> Unit
    val updateS3RcloneEncryptedSuffix: (String) -> Unit
    val updateS3AutoSyncEnabled: (Boolean) -> Unit
    val updateS3AutoSyncInterval: (String) -> Unit
    val updateS3SyncOnRefresh: (Boolean) -> Unit
    val triggerS3SyncNow: () -> Unit
    val testS3Connection: () -> Unit
}

interface SettingsS3FeatureSupport {
    fun resetConnectionTestState()
    fun isValidEndpointUrl(url: String): Boolean
}

class SettingsStorageFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
) {
    fun updateRootDirectory(path: String) {
        scope.launch { appConfigCoordinator.updateRootDirectory(path) }
    }

    fun updateRootUri(uriString: String) {
        scope.launch { appConfigCoordinator.updateRootUri(uriString) }
    }

    fun updateImageDirectory(path: String) {
        scope.launch { appConfigCoordinator.updateImageDirectory(path) }
    }

    fun updateImageUri(uriString: String) {
        scope.launch { appConfigCoordinator.updateImageUri(uriString) }
    }

    fun updateVoiceDirectory(path: String) {
        scope.launch { appConfigCoordinator.updateVoiceDirectory(path) }
    }

    fun updateVoiceUri(uriString: String) {
        scope.launch { appConfigCoordinator.updateVoiceUri(uriString) }
    }

    fun updateSyncInboxDirectory(path: String) {
        scope.launch { appConfigCoordinator.updateSyncInboxDirectory(path) }
    }

    fun updateSyncInboxUri(uriString: String) {
        scope.launch { appConfigCoordinator.updateSyncInboxUri(uriString) }
    }

    fun updateSyncInboxEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateSyncInboxEnabled(enabled) }
    }

    fun updateStorageFilenameFormat(format: String) {
        scope.launch { appConfigCoordinator.updateStorageFilenameFormat(format) }
    }

    fun updateStorageTimestampFormat(format: String) {
        scope.launch { appConfigCoordinator.updateStorageTimestampFormat(format) }
    }
}

class SettingsDisplayFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
) {
    fun updateDateFormat(format: String) {
        scope.launch { appConfigCoordinator.updateDateFormat(format) }
    }

    fun updateTimeFormat(format: String) {
        scope.launch { appConfigCoordinator.updateTimeFormat(format) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        scope.launch { appConfigCoordinator.updateThemeMode(mode) }
    }
}

class SettingsShareCardFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
) {
    fun updateShareCardShowTime(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateShareCardShowTime(enabled) }
    }

    fun updateShareCardShowBrand(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateShareCardShowBrand(enabled) }
    }

    fun updateShareCardSignatureText(text: String) {
        scope.launch { appConfigCoordinator.updateShareCardSignatureText(text) }
    }
}

class SettingsSnapshotFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
) {
    fun updateMemoSnapshotsEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateMemoSnapshotsEnabled(enabled) }
    }

    fun updateMemoSnapshotMaxCount(count: Int) {
        scope.launch { appConfigCoordinator.updateMemoSnapshotMaxCount(count) }
    }

    fun updateMemoSnapshotMaxAgeDays(days: Int) {
        scope.launch { appConfigCoordinator.updateMemoSnapshotMaxAgeDays(days) }
    }
}

class SettingsInteractionFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
) {
    fun updateHapticFeedback(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateHapticFeedback(enabled) }
    }

    fun updateShowInputHints(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateShowInputHints(enabled) }
    }

    fun updateDoubleTapEditEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateDoubleTapEditEnabled(enabled) }
    }

    fun updateFreeTextCopyEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateFreeTextCopyEnabled(enabled) }
    }

    fun updateMemoActionAutoReorderEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateMemoActionAutoReorderEnabled(enabled) }
    }

    fun updateQuickSaveOnBackEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateQuickSaveOnBackEnabled(enabled) }
    }

    fun updateScrollbarEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateScrollbarEnabled(enabled) }
    }

    fun updateAppLockEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateAppLockEnabled(enabled) }
    }
}

class SettingsSystemFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
    private val appUpdateChecker: AppUpdateChecker? = null,
    private val getCurrentAppVersionUseCase: GetCurrentAppVersionUseCase? = null,
    private val appUpdateDownloadManager: AppUpdateDownloadManager? = null,
) {
    private val _currentVersion = MutableStateFlow("")
    val currentVersion: StateFlow<String> = _currentVersion.asStateFlow()

    private val _manualUpdateState = MutableStateFlow<SettingsManualUpdateState>(SettingsManualUpdateState.Idle)
    val manualUpdateState: StateFlow<SettingsManualUpdateState> = _manualUpdateState.asStateFlow()
    private val _debugPreviewDialogState = MutableStateFlow<AppUpdateDialogState?>(null)
    val debugPreviewDialogState: StateFlow<AppUpdateDialogState?> = _debugPreviewDialogState.asStateFlow()
    private var debugPreviewJob: Job? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            _currentVersion.value = getCurrentAppVersionUseCase?.invoke().orEmpty()
        }
    }

    fun updateCheckUpdatesOnStartup(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateCheckUpdatesOnStartup(enabled) }
    }

    fun checkForUpdatesManually() {
        if (_manualUpdateState.value is SettingsManualUpdateState.Checking) {
            return
        }
        _manualUpdateState.value = SettingsManualUpdateState.Checking
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            _manualUpdateState.value =
                runCatching { appUpdateChecker?.checkForManualUpdate() }
                    .fold(
                        onSuccess = { info ->
                            when {
                                info == null -> SettingsManualUpdateState.UpToDate
                                else ->
                                    SettingsManualUpdateState.UpdateAvailable(
                                        dialogState =
                                            AppUpdateDialogState(
                                                url = info.url,
                                                version = info.version,
                                                releaseNotes = info.releaseNotes,
                                                apkDownloadUrl = info.apkDownloadUrl,
                                                apkFileName = info.apkFileName,
                                                apkSizeBytes = info.apkSizeBytes,
                                            ),
                                    )
                            }
                        },
                        onFailure = { throwable ->
                            SettingsManualUpdateState.Error(throwable.message)
                        },
                    )
        }
    }

    fun startInAppUpdate(dialogState: AppUpdateDialogState) {
        appUpdateDownloadManager?.startInAppUpdate(dialogState)
    }

    fun openDebugLatestReleasePreview() {
        if (debugPreviewJob?.isActive == true) {
            return
        }
        debugPreviewJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { appUpdateChecker?.getLatestReleaseForDebugPreview() }
                    .fold(
                        onSuccess = { info ->
                            when {
                                info == null -> _manualUpdateState.value = SettingsManualUpdateState.UpToDate
                                else ->
                                    _debugPreviewDialogState.value =
                                        AppUpdateDialogState(
                                            url = info.url,
                                            version = info.version,
                                            releaseNotes = info.releaseNotes,
                                            apkDownloadUrl = info.apkDownloadUrl,
                                            apkFileName = info.apkFileName,
                                            apkSizeBytes = info.apkSizeBytes,
                                        )
                            }
                        },
                        onFailure = { throwable ->
                            _manualUpdateState.value = SettingsManualUpdateState.Error(throwable.message)
                        },
                    )
            }.also { launchedJob ->
                launchedJob.invokeOnCompletion {
                    debugPreviewJob = null
                }
            }
    }

    fun consumeDebugPreviewDialog() {
        _debugPreviewDialogState.value = null
    }
}

sealed interface SettingsManualUpdateState {
    data object Idle : SettingsManualUpdateState

    data object Checking : SettingsManualUpdateState

    data class UpdateAvailable(
        val dialogState: AppUpdateDialogState,
    ) : SettingsManualUpdateState

    data object UpToDate : SettingsManualUpdateState

    data class Error(
        val message: String?,
    ) : SettingsManualUpdateState
}

class SettingsLanShareFeatureViewModel(
    private val actionCoordinator: SettingsLanShareFeatureActions,
    private val lanShareCoordinator: SettingsLanShareFeatureSupport,
) {
    fun updateLanShareE2eEnabled(enabled: Boolean) {
        actionCoordinator.updateLanShareE2eEnabled(enabled)
    }

    fun updateLanSharePairingCode(pairingCode: String) {
        actionCoordinator.updateLanSharePairingCode(pairingCode)
    }

    fun clearLanSharePairingCode() {
        actionCoordinator.clearLanSharePairingCode()
    }

    fun clearPairingCodeError() {
        lanShareCoordinator.clearPairingCodeError()
    }

    fun updateLanShareDeviceName(deviceName: String) {
        actionCoordinator.updateLanShareDeviceName(deviceName)
    }
}

class SettingsGitFeatureViewModel(
    actionCoordinator: SettingsGitFeatureActions,
    gitCoordinator: SettingsGitFeatureSupport,
) {
    val updateGitSyncEnabled = actionCoordinator.updateGitSyncEnabled
    val updateGitRemoteUrl = actionCoordinator.updateGitRemoteUrl
    val isValidGitRemoteUrl = gitCoordinator.isValidGitRemoteUrl
    val shouldShowGitConflictDialog = gitCoordinator.shouldShowGitConflictDialog
    val updateGitPat = actionCoordinator.updateGitPat
    val updateGitAuthorName = actionCoordinator.updateGitAuthorName
    val updateGitAuthorEmail = actionCoordinator.updateGitAuthorEmail
    val updateGitAutoSyncEnabled = actionCoordinator.updateGitAutoSyncEnabled
    val updateGitAutoSyncInterval = actionCoordinator.updateGitAutoSyncInterval
    val updateGitSyncOnRefresh = actionCoordinator.updateGitSyncOnRefresh
    val triggerGitSyncNow = actionCoordinator.triggerGitSyncNow
    val resolveGitConflictUsingRemote = actionCoordinator.resolveGitConflictUsingRemote
    val resolveGitConflictUsingLocal = actionCoordinator.resolveGitConflictUsingLocal
    val testGitConnection = actionCoordinator.testGitConnection
    val resetConnectionTestState = gitCoordinator.resetConnectionTestState
    val resetGitRepository = actionCoordinator.resetGitRepository
}

class SettingsWebDavFeatureViewModel(
    actionCoordinator: SettingsWebDavFeatureActions,
    webDavCoordinator: SettingsWebDavFeatureSupport,
) {
    val updateWebDavSyncEnabled = actionCoordinator.updateWebDavSyncEnabled
    val updateProvider = actionCoordinator.updateWebDavProvider
    val updateBaseUrl = actionCoordinator.updateWebDavBaseUrl
    val updateEndpointUrl = actionCoordinator.updateWebDavEndpointUrl
    val updateUsername = actionCoordinator.updateWebDavUsername
    val updatePassword = actionCoordinator.updateWebDavPassword
    val updateAutoSyncEnabled = actionCoordinator.updateWebDavAutoSyncEnabled
    val updateAutoSyncInterval = actionCoordinator.updateWebDavAutoSyncInterval
    val updateSyncOnRefresh = actionCoordinator.updateWebDavSyncOnRefresh
    val triggerSyncNow = actionCoordinator.triggerWebDavSyncNow
    val testConnection = actionCoordinator.testWebDavConnection
    val resetConnectionTestState = webDavCoordinator::resetConnectionTestState
    val isValidUrl = webDavCoordinator::isValidWebDavUrl
    val isValidWebDavUrl = webDavCoordinator::isValidWebDavUrl
}

class SettingsS3FeatureViewModel(
    actionCoordinator: SettingsS3FeatureActions,
    s3Coordinator: SettingsS3FeatureSupport,
) {
    val updateS3SyncEnabled = actionCoordinator.updateS3SyncEnabled
    val updateEndpointUrl = actionCoordinator.updateS3EndpointUrl
    val updateRegion = actionCoordinator.updateS3Region
    val updateBucket = actionCoordinator.updateS3Bucket
    val updatePrefix = actionCoordinator.updateS3Prefix
    val updateLocalSyncDirectory = actionCoordinator.updateS3LocalSyncDirectory
    val clearLocalSyncDirectory = actionCoordinator.clearS3LocalSyncDirectory
    val updateAccessKeyId = actionCoordinator.updateS3AccessKeyId
    val updateSecretAccessKey = actionCoordinator.updateS3SecretAccessKey
    val updateSessionToken = actionCoordinator.updateS3SessionToken
    val updatePathStyle: (S3PathStyle) -> Unit = actionCoordinator.updateS3PathStyle
    val updateEncryptionMode: (S3EncryptionMode) -> Unit = actionCoordinator.updateS3EncryptionMode
    val updateEncryptionPassword = actionCoordinator.updateS3EncryptionPassword
    val updateEncryptionPassword2 = actionCoordinator.updateS3EncryptionPassword2
    val updateRcloneFilenameEncryption: (S3RcloneFilenameEncryption) -> Unit =
        actionCoordinator.updateS3RcloneFilenameEncryption
    val updateRcloneFilenameEncoding: (S3RcloneFilenameEncoding) -> Unit =
        actionCoordinator.updateS3RcloneFilenameEncoding
    val updateRcloneDirectoryNameEncryption = actionCoordinator.updateS3RcloneDirectoryNameEncryption
    val updateRcloneDataEncryptionEnabled = actionCoordinator.updateS3RcloneDataEncryptionEnabled
    val updateRcloneEncryptedSuffix = actionCoordinator.updateS3RcloneEncryptedSuffix
    val updateAutoSyncEnabled = actionCoordinator.updateS3AutoSyncEnabled
    val updateAutoSyncInterval = actionCoordinator.updateS3AutoSyncInterval
    val updateSyncOnRefresh = actionCoordinator.updateS3SyncOnRefresh
    val triggerSyncNow = actionCoordinator.triggerS3SyncNow
    val testConnection = actionCoordinator.testS3Connection
    val resetConnectionTestState = s3Coordinator::resetConnectionTestState
    val isValidEndpointUrl = s3Coordinator::isValidEndpointUrl
}
