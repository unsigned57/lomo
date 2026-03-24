package com.lomo.app.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsActionCoordinator(
    private val scope: CoroutineScope,
    private val lanShareCoordinator: SettingsLanShareCoordinator,
    private val gitCoordinator: SettingsGitCoordinator,
    private val webDavCoordinator: SettingsWebDavCoordinator,
    private val errorMapper: SettingsOperationErrorMapper,
    private val onOperationError: (SettingsOperationError) -> Unit,
) {
    val refreshPatConfigured: () -> Unit =
        { launchWithOperationResult { gitCoordinator.refreshPatConfigured() } }

    val refreshWebDavPasswordConfigured: () -> Unit =
        { launchWithOperationResult { webDavCoordinator.refreshPasswordConfigured() } }

    val updateLanShareE2eEnabled: (Boolean) -> Unit =
        { enabled ->
            launchWithError("Failed to update secure share setting") {
                lanShareCoordinator.updateLanShareE2eEnabled(enabled)
            }
        }

    val updateLanSharePairingCode: (String) -> Unit =
        { pairingCode ->
            scope.launch {
                lanShareCoordinator.updateLanSharePairingCode(pairingCode)
            }
        }

    val clearLanSharePairingCode: () -> Unit =
        {
            launchWithError("Failed to clear pairing code") {
                lanShareCoordinator.clearLanSharePairingCode()
            }
        }

    val updateLanShareDeviceName: (String) -> Unit =
        { deviceName ->
            launchWithError("Failed to update LAN share device name") {
                lanShareCoordinator.updateLanShareDeviceName(deviceName)
            }
        }

    val updateGitSyncEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { gitCoordinator.updateGitSyncEnabled(enabled) } }

    val updateGitRemoteUrl: (String) -> Unit =
        { url -> launchWithOperationResult { gitCoordinator.updateGitRemoteUrl(url) } }

    val updateGitPat: (String) -> Unit =
        { token -> launchWithOperationResult { gitCoordinator.updateGitPat(token) } }

    val updateGitAuthorName: (String) -> Unit =
        { name -> launchWithOperationResult { gitCoordinator.updateGitAuthorName(name) } }

    val updateGitAuthorEmail: (String) -> Unit =
        { email -> launchWithOperationResult { gitCoordinator.updateGitAuthorEmail(email) } }

    val updateGitAutoSyncEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { gitCoordinator.updateGitAutoSyncEnabled(enabled) } }

    val updateGitAutoSyncInterval: (String) -> Unit =
        { interval -> launchWithOperationResult { gitCoordinator.updateGitAutoSyncInterval(interval) } }

    val updateGitSyncOnRefresh: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { gitCoordinator.updateGitSyncOnRefresh(enabled) } }

    val triggerGitSyncNow: () -> Unit =
        { launchWithOperationResult { gitCoordinator.triggerGitSyncNow() } }

    val resolveGitConflictUsingRemote: () -> Unit =
        { launchWithOperationResult { gitCoordinator.resolveGitConflictUsingRemote() } }

    val resolveGitConflictUsingLocal: () -> Unit =
        { launchWithOperationResult { gitCoordinator.resolveGitConflictUsingLocal() } }

    val testGitConnection: () -> Unit =
        { launchWithOperationResult { gitCoordinator.testGitConnection() } }

    val resetGitRepository: () -> Unit =
        { launchWithOperationResult { gitCoordinator.resetGitRepository() } }

    val updateWebDavSyncEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { webDavCoordinator.updateWebDavSyncEnabled(enabled) } }

    val updateWebDavProvider: (com.lomo.domain.model.WebDavProvider) -> Unit =
        { provider -> launchWithOperationResult { webDavCoordinator.updateWebDavProvider(provider) } }

    val updateWebDavBaseUrl: (String) -> Unit =
        { url -> launchWithOperationResult { webDavCoordinator.updateWebDavBaseUrl(url) } }

    val updateWebDavEndpointUrl: (String) -> Unit =
        { url -> launchWithOperationResult { webDavCoordinator.updateWebDavEndpointUrl(url) } }

    val updateWebDavUsername: (String) -> Unit =
        { username -> launchWithOperationResult { webDavCoordinator.updateWebDavUsername(username) } }

    val updateWebDavPassword: (String) -> Unit =
        { password -> launchWithOperationResult { webDavCoordinator.updateWebDavPassword(password) } }

    val updateWebDavAutoSyncEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { webDavCoordinator.updateWebDavAutoSyncEnabled(enabled) } }

    val updateWebDavAutoSyncInterval: (String) -> Unit =
        { interval -> launchWithOperationResult { webDavCoordinator.updateWebDavAutoSyncInterval(interval) } }

    val updateWebDavSyncOnRefresh: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { webDavCoordinator.updateWebDavSyncOnRefresh(enabled) } }

    val triggerWebDavSyncNow: () -> Unit =
        { launchWithOperationResult { webDavCoordinator.triggerWebDavSyncNow() } }

    val testWebDavConnection: () -> Unit =
        { launchWithOperationResult { webDavCoordinator.testWebDavConnection() } }

    private fun launchWithOperationResult(action: suspend () -> SettingsOperationError?) {
        scope.launch {
            val error = action()
            if (error != null) {
                onOperationError(error)
            }
        }
    }

    private fun launchWithError(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            runCatching { action() }
                .onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    onOperationError(errorMapper.map(throwable, fallbackMessage))
                }
        }
    }
}
