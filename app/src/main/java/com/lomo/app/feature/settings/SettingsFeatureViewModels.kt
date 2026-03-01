package com.lomo.app.feature.settings

import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
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
    fun updateShareCardStyle(style: ShareCardStyle) {
        scope.launch { appConfigCoordinator.updateShareCardStyle(style) }
    }

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
    private val actionCoordinator: SettingsActionCoordinator,
    private val gitCoordinator: SettingsGitCoordinator,
) {
    fun updateGitSyncEnabled(enabled: Boolean) {
        actionCoordinator.updateGitSyncEnabled(enabled)
    }

    fun updateGitRemoteUrl(url: String) {
        actionCoordinator.updateGitRemoteUrl(url)
    }

    fun isValidGitRemoteUrl(url: String): Boolean = gitCoordinator.isValidGitRemoteUrl(url)

    fun shouldShowGitConflictDialog(message: String): Boolean = gitCoordinator.shouldShowGitConflictDialog(message)

    fun presentGitSyncErrorMessage(
        message: String,
        conflictSummary: String,
        directPathRequired: String,
        unknownError: String,
    ): String =
        gitCoordinator.presentGitSyncErrorMessage(
            message = message,
            conflictSummary = conflictSummary,
            directPathRequired = directPathRequired,
            unknownError = unknownError,
        )

    fun updateGitPat(token: String) {
        actionCoordinator.updateGitPat(token)
    }

    fun updateGitAuthorName(name: String) {
        actionCoordinator.updateGitAuthorName(name)
    }

    fun updateGitAuthorEmail(email: String) {
        actionCoordinator.updateGitAuthorEmail(email)
    }

    fun updateGitAutoSyncEnabled(enabled: Boolean) {
        actionCoordinator.updateGitAutoSyncEnabled(enabled)
    }

    fun updateGitAutoSyncInterval(interval: String) {
        actionCoordinator.updateGitAutoSyncInterval(interval)
    }

    fun updateGitSyncOnRefresh(enabled: Boolean) {
        actionCoordinator.updateGitSyncOnRefresh(enabled)
    }

    fun triggerGitSyncNow() {
        actionCoordinator.triggerGitSyncNow()
    }

    fun resolveGitConflictUsingRemote() {
        actionCoordinator.resolveGitConflictUsingRemote()
    }

    fun resolveGitConflictUsingLocal() {
        actionCoordinator.resolveGitConflictUsingLocal()
    }

    fun testGitConnection() {
        actionCoordinator.testGitConnection()
    }

    fun resetConnectionTestState() {
        gitCoordinator.resetConnectionTestState()
    }

    fun resetGitRepository() {
        actionCoordinator.resetGitRepository()
    }
}
