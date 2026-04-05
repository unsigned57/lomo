package com.lomo.app.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsActionCoordinator(
    private val scope: CoroutineScope,
    private val lanShareCoordinator: SettingsLanShareCoordinator,
    private val gitCoordinator: SettingsGitCoordinator,
    private val webDavCoordinator: SettingsWebDavCoordinator,
    private val s3Coordinator: SettingsS3Coordinator,
    private val errorMapper: SettingsOperationErrorMapper,
    private val onOperationError: (SettingsOperationError) -> Unit,
) {
    val refreshPatConfigured: () -> Unit =
        { launchWithOperationResult { gitCoordinator.refreshPatConfigured() } }

    val refreshWebDavPasswordConfigured: () -> Unit =
        { launchWithOperationResult { webDavCoordinator.refreshPasswordConfigured() } }

    val refreshS3CredentialConfigured: () -> Unit =
        { launchWithOperationResult { s3Coordinator.refreshCredentialConfigured() } }

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

    val updateS3SyncEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3SyncEnabled(enabled) } }

    val updateS3EndpointUrl: (String) -> Unit =
        { url -> launchWithOperationResult { s3Coordinator.updateS3EndpointUrl(url) } }

    val updateS3Region: (String) -> Unit =
        { region -> launchWithOperationResult { s3Coordinator.updateS3Region(region) } }

    val updateS3Bucket: (String) -> Unit =
        { bucket -> launchWithOperationResult { s3Coordinator.updateS3Bucket(bucket) } }

    val updateS3Prefix: (String) -> Unit =
        { prefix -> launchWithOperationResult { s3Coordinator.updateS3Prefix(prefix) } }

    val updateS3LocalSyncDirectory: (String) -> Unit =
        { directory -> launchWithOperationResult { s3Coordinator.updateS3LocalSyncDirectory(directory) } }

    val clearS3LocalSyncDirectory: () -> Unit =
        { launchWithOperationResult { s3Coordinator.clearS3LocalSyncDirectory() } }

    val updateS3AccessKeyId: (String) -> Unit =
        { accessKeyId -> launchWithOperationResult { s3Coordinator.updateS3AccessKeyId(accessKeyId) } }

    val updateS3SecretAccessKey: (String) -> Unit =
        { secret -> launchWithOperationResult { s3Coordinator.updateS3SecretAccessKey(secret) } }

    val updateS3SessionToken: (String) -> Unit =
        { token -> launchWithOperationResult { s3Coordinator.updateS3SessionToken(token) } }

    val updateS3PathStyle: (com.lomo.domain.model.S3PathStyle) -> Unit =
        { style -> launchWithOperationResult { s3Coordinator.updateS3PathStyle(style) } }

    val updateS3EncryptionMode: (com.lomo.domain.model.S3EncryptionMode) -> Unit =
        { mode -> launchWithOperationResult { s3Coordinator.updateS3EncryptionMode(mode) } }

    val updateS3EncryptionPassword: (String) -> Unit =
        { password -> launchWithOperationResult { s3Coordinator.updateS3EncryptionPassword(password) } }

    val updateS3EncryptionPassword2: (String) -> Unit =
        { password -> launchWithOperationResult { s3Coordinator.updateS3EncryptionPassword2(password) } }

    val updateS3RcloneFilenameEncryption: (com.lomo.domain.model.S3RcloneFilenameEncryption) -> Unit =
        { mode -> launchWithOperationResult { s3Coordinator.updateS3RcloneFilenameEncryption(mode) } }

    val updateS3RcloneFilenameEncoding: (com.lomo.domain.model.S3RcloneFilenameEncoding) -> Unit =
        { encoding -> launchWithOperationResult { s3Coordinator.updateS3RcloneFilenameEncoding(encoding) } }

    val updateS3RcloneDirectoryNameEncryption: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3RcloneDirectoryNameEncryption(enabled) } }

    val updateS3RcloneDataEncryptionEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3RcloneDataEncryptionEnabled(enabled) } }

    val updateS3RcloneEncryptedSuffix: (String) -> Unit =
        { suffix -> launchWithOperationResult { s3Coordinator.updateS3RcloneEncryptedSuffix(suffix) } }

    val updateS3AutoSyncEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3AutoSyncEnabled(enabled) } }

    val updateS3AutoSyncInterval: (String) -> Unit =
        { interval -> launchWithOperationResult { s3Coordinator.updateS3AutoSyncInterval(interval) } }

    val updateS3SyncOnRefresh: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3SyncOnRefresh(enabled) } }

    val triggerS3SyncNow: () -> Unit =
        { launchWithOperationResult { s3Coordinator.triggerS3SyncNow() } }

    val testS3Connection: () -> Unit =
        { launchWithOperationResult { s3Coordinator.testS3Connection() } }

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
