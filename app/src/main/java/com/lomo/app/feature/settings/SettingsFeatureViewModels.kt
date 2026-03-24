package com.lomo.app.feature.settings

import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    fun updateQuickSaveOnBackEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateQuickSaveOnBackEnabled(enabled) }
    }

    fun updateAppLockEnabled(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateAppLockEnabled(enabled) }
    }
}

class SettingsSystemFeatureViewModel(
    private val scope: CoroutineScope,
    private val appConfigCoordinator: SettingsAppConfigCoordinator,
) {
    fun updateCheckUpdatesOnStartup(enabled: Boolean) {
        scope.launch { appConfigCoordinator.updateCheckUpdatesOnStartup(enabled) }
    }
}

class SettingsLanShareFeatureViewModel(
    private val actionCoordinator: SettingsActionCoordinator,
    private val lanShareCoordinator: SettingsLanShareCoordinator,
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
    actionCoordinator: SettingsActionCoordinator,
    gitCoordinator: SettingsGitCoordinator,
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
    actionCoordinator: SettingsActionCoordinator,
    webDavCoordinator: SettingsWebDavCoordinator,
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
