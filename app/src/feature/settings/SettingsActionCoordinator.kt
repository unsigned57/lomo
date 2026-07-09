package com.lomo.app.feature.settings

import com.lomo.domain.model.SyncBackendType
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
) : SettingsLanShareFeatureActions,
    SettingsRemoteProviderFeatureActions,
    SettingsGitSpecificFeatureActions,
    SettingsWebDavSpecificFeatureActions,
    SettingsS3SpecificFeatureActions {
    val refreshPatConfigured: () -> Unit =
        { launchWithOperationResult { gitCoordinator.refreshPatConfigured() } }

    val refreshWebDavPasswordConfigured: () -> Unit =
        { launchWithOperationResult { webDavCoordinator.refreshPasswordConfigured() } }

    val refreshS3CredentialConfigured: () -> Unit =
        { launchWithOperationResult { s3Coordinator.refreshCredentialConfigured() } }

    override val updateLanShareEnabled: (Boolean) -> Unit =
        { enabled ->
            launchWithError("Failed to update LAN share setting") {
                lanShareCoordinator.updateLanShareEnabled(enabled)
            }
        }

    override val updateLanShareE2eEnabled: (Boolean) -> Unit =
        { enabled ->
            launchWithError("Failed to update secure share setting") {
                lanShareCoordinator.updateLanShareE2eEnabled(enabled)
            }
        }

    override val updateLanSharePairingCode: (String) -> Unit =
        { pairingCode ->
            scope.launch {
                lanShareCoordinator.updateLanSharePairingCode(pairingCode)
            }
        }

    override val clearLanSharePairingCode: () -> Unit =
        {
            launchWithError("Failed to clear pairing code") {
                lanShareCoordinator.clearLanSharePairingCode()
            }
        }

    override val updateLanShareDeviceName: (String) -> Unit =
        { deviceName ->
            launchWithError("Failed to update LAN share device name") {
                lanShareCoordinator.updateLanShareDeviceName(deviceName)
            }
        }

    override val updateProviderEnabled: (SyncBackendType, Boolean) -> Unit =
        { provider, enabled ->
            launchWithOperationResult {
                providerSettingsActions(provider).updateEnabled(enabled)
            }
        }

    override val updateProviderAutoSyncEnabled: (SyncBackendType, Boolean) -> Unit =
        { provider, enabled ->
            launchWithOperationResult {
                providerSettingsActions(provider).updateAutoSyncEnabled(enabled)
            }
        }

    override val updateProviderAutoSyncInterval: (SyncBackendType, String) -> Unit =
        { provider, interval ->
            launchWithOperationResult {
                providerSettingsActions(provider).updateAutoSyncInterval(interval)
            }
        }

    override val updateProviderSyncOnRefresh: (SyncBackendType, Boolean) -> Unit =
        { provider, enabled ->
            launchWithOperationResult {
                providerSettingsActions(provider).updateSyncOnRefreshEnabled(enabled)
            }
        }

    override val triggerProviderSyncNow: (SyncBackendType) -> Unit =
        { provider ->
            launchWithOperationResult {
                providerSettingsActions(provider).triggerSyncNow()
            }
        }

    override val testProviderConnection: (SyncBackendType) -> Unit =
        { provider ->
            launchWithOperationResult {
                providerSettingsActions(provider).testConnection()
            }
        }

    override val updateGitRemoteUrl: (String) -> Unit =
        { url -> launchWithOperationResult { gitCoordinator.updateGitRemoteUrl(url) } }

    override val updateGitPat: (String) -> Unit =
        { token -> launchWithOperationResult { gitCoordinator.updateGitPat(token) } }

    override val updateGitAuthorName: (String) -> Unit =
        { name -> launchWithOperationResult { gitCoordinator.updateGitAuthorName(name) } }

    override val updateGitAuthorEmail: (String) -> Unit =
        { email -> launchWithOperationResult { gitCoordinator.updateGitAuthorEmail(email) } }

    override val resolveGitConflictUsingRemote: () -> Unit =
        { launchWithOperationResult { gitCoordinator.resolveGitConflictUsingRemote() } }

    override val resolveGitConflictUsingLocal: () -> Unit =
        { launchWithOperationResult { gitCoordinator.resolveGitConflictUsingLocal() } }

    override val resetGitRepository: () -> Unit =
        { launchWithOperationResult { gitCoordinator.resetGitRepository() } }

    override val updateWebDavProvider: (com.lomo.domain.model.WebDavProvider) -> Unit =
        { provider -> launchWithOperationResult { webDavCoordinator.updateWebDavProvider(provider) } }

    override val updateWebDavBaseUrl: (String) -> Unit =
        { url -> launchWithOperationResult { webDavCoordinator.updateWebDavBaseUrl(url) } }

    override val updateWebDavEndpointUrl: (String) -> Unit =
        { url -> launchWithOperationResult { webDavCoordinator.updateWebDavEndpointUrl(url) } }

    override val updateWebDavUsername: (String) -> Unit =
        { username -> launchWithOperationResult { webDavCoordinator.updateWebDavUsername(username) } }

    override val updateWebDavPassword: (String) -> Unit =
        { password -> launchWithOperationResult { webDavCoordinator.updateWebDavPassword(password) } }

    override val updateS3EndpointUrl: (String) -> Unit =
        { url -> launchWithOperationResult { s3Coordinator.updateS3EndpointUrl(url) } }

    override val updateS3Region: (String) -> Unit =
        { region -> launchWithOperationResult { s3Coordinator.updateS3Region(region) } }

    override val updateS3Bucket: (String) -> Unit =
        { bucket -> launchWithOperationResult { s3Coordinator.updateS3Bucket(bucket) } }

    override val updateS3Prefix: (String) -> Unit =
        { prefix -> launchWithOperationResult { s3Coordinator.updateS3Prefix(prefix) } }

    override val updateS3LocalSyncDirectory: (String) -> Unit =
        { directory -> launchWithOperationResult { s3Coordinator.updateS3LocalSyncDirectory(directory) } }

    override val clearS3LocalSyncDirectory: () -> Unit =
        { launchWithOperationResult { s3Coordinator.clearS3LocalSyncDirectory() } }

    override val updateS3AccessKeyId: (String) -> Unit =
        { accessKeyId -> launchWithOperationResult { s3Coordinator.updateS3AccessKeyId(accessKeyId) } }

    override val updateS3SecretAccessKey: (String) -> Unit =
        { secret -> launchWithOperationResult { s3Coordinator.updateS3SecretAccessKey(secret) } }

    override val updateS3SessionToken: (String) -> Unit =
        { token -> launchWithOperationResult { s3Coordinator.updateS3SessionToken(token) } }

    override val updateS3PathStyle: (com.lomo.domain.model.S3PathStyle) -> Unit =
        { style -> launchWithOperationResult { s3Coordinator.updateS3PathStyle(style) } }

    override val updateS3EncryptionMode: (com.lomo.domain.model.S3EncryptionMode) -> Unit =
        { mode -> launchWithOperationResult { s3Coordinator.updateS3EncryptionMode(mode) } }

    override val updateS3EncryptionPassword: (String) -> Unit =
        { password -> launchWithOperationResult { s3Coordinator.updateS3EncryptionPassword(password) } }

    override val updateS3EncryptionPassword2: (String) -> Unit =
        { password -> launchWithOperationResult { s3Coordinator.updateS3EncryptionPassword2(password) } }

    override val updateS3RcloneFilenameEncryption: (com.lomo.domain.model.S3RcloneFilenameEncryption) -> Unit =
        { mode -> launchWithOperationResult { s3Coordinator.updateS3RcloneFilenameEncryption(mode) } }

    override val updateS3RcloneFilenameEncoding: (com.lomo.domain.model.S3RcloneFilenameEncoding) -> Unit =
        { encoding -> launchWithOperationResult { s3Coordinator.updateS3RcloneFilenameEncoding(encoding) } }

    override val updateS3RcloneDirectoryNameEncryption: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3RcloneDirectoryNameEncryption(enabled) } }

    override val updateS3RcloneDataEncryptionEnabled: (Boolean) -> Unit =
        { enabled -> launchWithOperationResult { s3Coordinator.updateS3RcloneDataEncryptionEnabled(enabled) } }

    override val updateS3RcloneEncryptedSuffix: (String) -> Unit =
        { suffix -> launchWithOperationResult { s3Coordinator.updateS3RcloneEncryptedSuffix(suffix) } }

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

    private fun providerSettingsActions(provider: SyncBackendType): RemoteProviderSettingsActionTarget =
        when (provider) {
            SyncBackendType.GIT -> gitCoordinator.providerSettingsActions
            SyncBackendType.WEBDAV -> webDavCoordinator.providerSettingsActions
            SyncBackendType.S3 -> s3Coordinator.providerSettingsActions
            SyncBackendType.INBOX,
            SyncBackendType.NONE,
            -> unsupportedRemoteProvider(provider)
        }

    private fun unsupportedRemoteProvider(provider: SyncBackendType): Nothing =
        error("Provider $provider does not support remote provider settings actions")
}
