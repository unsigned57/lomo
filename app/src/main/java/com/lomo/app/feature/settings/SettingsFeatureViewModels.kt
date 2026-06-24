package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDownloadManager
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.SyncBackendType
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
    val updateLanShareEnabled: (Boolean) -> Unit
    val updateLanShareE2eEnabled: (Boolean) -> Unit
    val updateLanSharePairingCode: (String) -> Unit
    val clearLanSharePairingCode: () -> Unit
    val updateLanShareDeviceName: (String) -> Unit
}

interface SettingsLanShareFeatureSupport {
    fun clearPairingCodeError()
}

interface SettingsRemoteProviderFeatureActions {
    val updateProviderEnabled: (SyncBackendType, Boolean) -> Unit
    val updateProviderAutoSyncEnabled: (SyncBackendType, Boolean) -> Unit
    val updateProviderAutoSyncInterval: (SyncBackendType, String) -> Unit
    val updateProviderSyncOnRefresh: (SyncBackendType, Boolean) -> Unit
    val triggerProviderSyncNow: (SyncBackendType) -> Unit
    val testProviderConnection: (SyncBackendType) -> Unit
}

interface SettingsGitSpecificFeatureActions {
    val updateGitRemoteUrl: (String) -> Unit
    val updateGitPat: (String) -> Unit
    val updateGitAuthorName: (String) -> Unit
    val updateGitAuthorEmail: (String) -> Unit
    val resolveGitConflictUsingRemote: () -> Unit
    val resolveGitConflictUsingLocal: () -> Unit
    val resetGitRepository: () -> Unit
}

interface SettingsGitFeatureSupport {
    val isValidGitRemoteUrl: (String) -> Boolean
    val shouldShowGitConflictDialog: (GitSyncErrorCode) -> Boolean
    val resetConnectionTestState: () -> Unit
}

interface SettingsWebDavSpecificFeatureActions {
    val updateWebDavProvider: (WebDavProvider) -> Unit
    val updateWebDavBaseUrl: (String) -> Unit
    val updateWebDavEndpointUrl: (String) -> Unit
    val updateWebDavUsername: (String) -> Unit
    val updateWebDavPassword: (String) -> Unit
}

interface SettingsWebDavFeatureSupport {
    fun resetConnectionTestState()
    fun isValidWebDavUrl(url: String): Boolean
}

interface SettingsS3SpecificFeatureActions {
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

    fun updateColorSource(source: ColorSource) {
        scope.launch { appConfigCoordinator.updateColorSource(source) }
    }

    fun updateFontPreference(preference: FontPreference) {
        scope.launch { appConfigCoordinator.updateFontPreference(preference) }
    }

    fun importCustomFont(
        contents: ByteArray,
        originalFileName: String,
        onResult: (CustomFontInfo?) -> Unit = {},
    ) {
        scope.launch {
            val result = appConfigCoordinator.importCustomFont(contents, originalFileName)
            onResult(result)
        }
    }

    fun deleteCustomFont(id: String) {
        scope.launch { appConfigCoordinator.deleteCustomFont(id) }
    }

    fun updateTypographyFontSizeScale(scale: Float) {
        scope.launch { appConfigCoordinator.updateTypographyFontSizeScale(scale) }
    }

    fun updateTypographyLineHeightScale(scale: Float) {
        scope.launch { appConfigCoordinator.updateTypographyLineHeightScale(scale) }
    }

    fun updateTypographyLetterSpacingScale(scale: Float) {
        scope.launch { appConfigCoordinator.updateTypographyLetterSpacingScale(scale) }
    }

    fun updateTypographyParagraphSpacingScale(scale: Float) {
        scope.launch { appConfigCoordinator.updateTypographyParagraphSpacingScale(scale) }
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
                                                expectedPackageName = info.expectedPackageName,
                                                expectedVersionName = info.expectedVersionName,
                                                expectedVersionCode = info.expectedVersionCode,
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
                                            expectedPackageName = info.expectedPackageName,
                                            expectedVersionName = info.expectedVersionName,
                                            expectedVersionCode = info.expectedVersionCode,
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
    fun updateLanShareEnabled(enabled: Boolean) {
        actionCoordinator.updateLanShareEnabled(enabled)
    }

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
    providerActions: SettingsRemoteProviderFeatureActions,
    gitActions: SettingsGitSpecificFeatureActions,
    gitCoordinator: SettingsGitFeatureSupport,
) {
    val provider =
        SettingsRemoteProviderFeatureViewModel(
            provider = SyncBackendType.GIT,
            actionCoordinator = providerActions,
            resetConnectionTestState = gitCoordinator.resetConnectionTestState,
        )
    val updateGitRemoteUrl = gitActions.updateGitRemoteUrl
    val isValidGitRemoteUrl = gitCoordinator.isValidGitRemoteUrl
    val shouldShowGitConflictDialog = gitCoordinator.shouldShowGitConflictDialog
    val updateGitPat = gitActions.updateGitPat
    val updateGitAuthorName = gitActions.updateGitAuthorName
    val updateGitAuthorEmail = gitActions.updateGitAuthorEmail
    val resolveGitConflictUsingRemote = gitActions.resolveGitConflictUsingRemote
    val resolveGitConflictUsingLocal = gitActions.resolveGitConflictUsingLocal
    val resetGitRepository = gitActions.resetGitRepository
}

class SettingsWebDavFeatureViewModel(
    providerActions: SettingsRemoteProviderFeatureActions,
    webDavActions: SettingsWebDavSpecificFeatureActions,
    webDavCoordinator: SettingsWebDavFeatureSupport,
) {
    val provider =
        SettingsRemoteProviderFeatureViewModel(
            provider = SyncBackendType.WEBDAV,
            actionCoordinator = providerActions,
            resetConnectionTestState = webDavCoordinator::resetConnectionTestState,
        )
    val updateProvider = webDavActions.updateWebDavProvider
    val updateBaseUrl = webDavActions.updateWebDavBaseUrl
    val updateEndpointUrl = webDavActions.updateWebDavEndpointUrl
    val updateUsername = webDavActions.updateWebDavUsername
    val updatePassword = webDavActions.updateWebDavPassword
    val isValidUrl = webDavCoordinator::isValidWebDavUrl
    val isValidWebDavUrl = webDavCoordinator::isValidWebDavUrl
}

class SettingsS3FeatureViewModel(
    providerActions: SettingsRemoteProviderFeatureActions,
    s3Actions: SettingsS3SpecificFeatureActions,
    s3Coordinator: SettingsS3FeatureSupport,
) {
    val provider =
        SettingsRemoteProviderFeatureViewModel(
            provider = SyncBackendType.S3,
            actionCoordinator = providerActions,
            resetConnectionTestState = s3Coordinator::resetConnectionTestState,
        )
    val updateEndpointUrl = s3Actions.updateS3EndpointUrl
    val updateRegion = s3Actions.updateS3Region
    val updateBucket = s3Actions.updateS3Bucket
    val updatePrefix = s3Actions.updateS3Prefix
    val updateLocalSyncDirectory = s3Actions.updateS3LocalSyncDirectory
    val clearLocalSyncDirectory = s3Actions.clearS3LocalSyncDirectory
    val updateAccessKeyId = s3Actions.updateS3AccessKeyId
    val updateSecretAccessKey = s3Actions.updateS3SecretAccessKey
    val updateSessionToken = s3Actions.updateS3SessionToken
    val updatePathStyle: (S3PathStyle) -> Unit = s3Actions.updateS3PathStyle
    val updateEncryptionMode: (S3EncryptionMode) -> Unit = s3Actions.updateS3EncryptionMode
    val updateEncryptionPassword = s3Actions.updateS3EncryptionPassword
    val updateEncryptionPassword2 = s3Actions.updateS3EncryptionPassword2
    val updateRcloneFilenameEncryption: (S3RcloneFilenameEncryption) -> Unit =
        s3Actions.updateS3RcloneFilenameEncryption
    val updateRcloneFilenameEncoding: (S3RcloneFilenameEncoding) -> Unit =
        s3Actions.updateS3RcloneFilenameEncoding
    val updateRcloneDirectoryNameEncryption = s3Actions.updateS3RcloneDirectoryNameEncryption
    val updateRcloneDataEncryptionEnabled = s3Actions.updateS3RcloneDataEncryptionEnabled
    val updateRcloneEncryptedSuffix = s3Actions.updateS3RcloneEncryptedSuffix
    val isValidEndpointUrl = s3Coordinator::isValidEndpointUrl
}

class SettingsRemoteProviderFeatureViewModel(
    private val provider: SyncBackendType,
    private val actionCoordinator: SettingsRemoteProviderFeatureActions,
    private val resetConnectionTestState: () -> Unit,
) {
    fun updateEnabled(enabled: Boolean) {
        actionCoordinator.updateProviderEnabled(provider, enabled)
    }

    fun updateAutoSyncEnabled(enabled: Boolean) {
        actionCoordinator.updateProviderAutoSyncEnabled(provider, enabled)
    }

    fun updateAutoSyncInterval(interval: String) {
        actionCoordinator.updateProviderAutoSyncInterval(provider, interval)
    }

    fun updateSyncOnRefresh(enabled: Boolean) {
        actionCoordinator.updateProviderSyncOnRefresh(provider, enabled)
    }

    fun triggerSyncNow() {
        actionCoordinator.triggerProviderSyncNow(provider)
    }

    fun testConnection() {
        actionCoordinator.testProviderConnection(provider)
    }

    fun resetConnectionTestState() {
        resetConnectionTestState.invoke()
    }
}
