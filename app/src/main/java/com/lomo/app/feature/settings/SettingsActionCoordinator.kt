package com.lomo.app.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsActionCoordinator(
    private val scope: CoroutineScope,
    private val lanShareCoordinator: SettingsLanShareCoordinator,
    private val gitCoordinator: SettingsGitCoordinator,
    private val errorMapper: SettingsOperationErrorMapper,
    private val onOperationError: (String) -> Unit,
) {
    fun refreshPatConfigured() = launchWithOperationResult { gitCoordinator.refreshPatConfigured() }

    fun updateLanShareE2eEnabled(enabled: Boolean) {
        launchWithError("Failed to update secure share setting") {
            lanShareCoordinator.updateLanShareE2eEnabled(enabled)
        }
    }

    fun updateLanSharePairingCode(pairingCode: String) {
        scope.launch {
            try {
                lanShareCoordinator.updateLanSharePairingCode(pairingCode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    fun clearLanSharePairingCode() {
        launchWithError("Failed to clear pairing code") {
            lanShareCoordinator.clearLanSharePairingCode()
        }
    }

    fun updateLanShareDeviceName(deviceName: String) {
        launchWithError("Failed to update LAN share device name") {
            lanShareCoordinator.updateLanShareDeviceName(deviceName)
        }
    }

    fun updateGitSyncEnabled(enabled: Boolean) = launchWithOperationResult { gitCoordinator.updateGitSyncEnabled(enabled) }

    fun updateGitRemoteUrl(url: String) = launchWithOperationResult { gitCoordinator.updateGitRemoteUrl(url) }

    fun updateGitPat(token: String) = launchWithOperationResult { gitCoordinator.updateGitPat(token) }

    fun updateGitAuthorName(name: String) = launchWithOperationResult { gitCoordinator.updateGitAuthorName(name) }

    fun updateGitAuthorEmail(email: String) = launchWithOperationResult { gitCoordinator.updateGitAuthorEmail(email) }

    fun updateGitAutoSyncEnabled(enabled: Boolean) = launchWithOperationResult { gitCoordinator.updateGitAutoSyncEnabled(enabled) }

    fun updateGitAutoSyncInterval(interval: String) = launchWithOperationResult { gitCoordinator.updateGitAutoSyncInterval(interval) }

    fun updateGitSyncOnRefresh(enabled: Boolean) = launchWithOperationResult { gitCoordinator.updateGitSyncOnRefresh(enabled) }

    fun triggerGitSyncNow() = launchWithOperationResult { gitCoordinator.triggerGitSyncNow() }

    fun resolveGitConflictUsingRemote() = launchWithOperationResult { gitCoordinator.resolveGitConflictUsingRemote() }

    fun resolveGitConflictUsingLocal() = launchWithOperationResult { gitCoordinator.resolveGitConflictUsingLocal() }

    fun testGitConnection() = launchWithOperationResult { gitCoordinator.testGitConnection() }

    fun resetGitRepository() = launchWithOperationResult { gitCoordinator.resetGitRepository() }

    private fun launchWithOperationResult(action: suspend () -> String?) {
        scope.launch {
            try {
                val message = action()
                if (!message.isNullOrBlank()) {
                    onOperationError(message)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    private fun launchWithError(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                onOperationError(errorMapper.map(throwable, fallbackMessage))
            }
        }
    }
}
