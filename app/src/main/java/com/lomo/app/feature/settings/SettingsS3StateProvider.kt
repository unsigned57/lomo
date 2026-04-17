package com.lomo.app.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SettingsS3StateProvider(
    private val s3Coordinator: SettingsS3Coordinator,
    scope: CoroutineScope,
) {
    private data class PrimaryState(
        val enabled: Boolean,
        val endpointUrl: String,
        val region: String,
        val bucket: String,
        val prefix: String,
        val localSyncDirectory: String,
    )

    private data class CredentialState(
        val accessKeyConfigured: Boolean,
        val secretAccessKeyConfigured: Boolean,
        val sessionTokenConfigured: Boolean,
        val encryptionPasswordConfigured: Boolean,
        val encryptionPassword2Configured: Boolean,
    )

    private data class RoutingState(
        val pathStyle: com.lomo.domain.model.S3PathStyle,
        val encryptionMode: com.lomo.domain.model.S3EncryptionMode,
        val rcloneFilenameEncryption: com.lomo.domain.model.S3RcloneFilenameEncryption,
        val rcloneFilenameEncoding: com.lomo.domain.model.S3RcloneFilenameEncoding,
        val rcloneDirectoryNameEncryption: Boolean,
        val rcloneDataEncryptionEnabled: Boolean,
        val rcloneEncryptedSuffix: String,
    )

    private data class RcloneFilenameState(
        val filenameEncryption: com.lomo.domain.model.S3RcloneFilenameEncryption,
        val filenameEncoding: com.lomo.domain.model.S3RcloneFilenameEncoding,
        val directoryNameEncryption: Boolean,
        val encryptedSuffix: String,
    )

    private data class IdentityState(
        val enabled: Boolean,
        val endpointUrl: String,
        val region: String,
        val bucket: String,
        val prefix: String,
        val localSyncDirectory: String,
        val accessKeyConfigured: Boolean,
        val secretAccessKeyConfigured: Boolean,
        val sessionTokenConfigured: Boolean,
        val pathStyle: com.lomo.domain.model.S3PathStyle,
        val encryptionMode: com.lomo.domain.model.S3EncryptionMode,
        val encryptionPasswordConfigured: Boolean,
        val encryptionPassword2Configured: Boolean,
        val rcloneFilenameEncryption: com.lomo.domain.model.S3RcloneFilenameEncryption,
        val rcloneFilenameEncoding: com.lomo.domain.model.S3RcloneFilenameEncoding,
        val rcloneDirectoryNameEncryption: Boolean,
        val rcloneDataEncryptionEnabled: Boolean,
        val rcloneEncryptedSuffix: String,
    )

    private data class SyncSettingsState(
        val autoSyncEnabled: Boolean,
        val autoSyncInterval: String,
        val syncOnRefreshEnabled: Boolean,
        val lastSyncTime: Long,
        val syncState: com.lomo.domain.model.UnifiedSyncState,
    )

    val connectionTestState: StateFlow<SettingsS3ConnectionTestState> = s3Coordinator.connectionTestState

    private val primaryConnectionState: StateFlow<PrimaryState> =
        combine(
            s3Coordinator.s3SyncEnabled,
            s3Coordinator.s3EndpointUrl,
            s3Coordinator.s3Region,
            s3Coordinator.s3Bucket,
            s3Coordinator.s3Prefix,
        ) { enabled, endpointUrl, region, bucket, prefix ->
            PrimaryState(
                enabled = enabled,
                endpointUrl = endpointUrl,
                region = region,
                bucket = bucket,
                prefix = prefix,
                localSyncDirectory = "",
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                PrimaryState(
                    enabled = s3Coordinator.s3SyncEnabled.value,
                    endpointUrl = s3Coordinator.s3EndpointUrl.value,
                    region = s3Coordinator.s3Region.value,
                    bucket = s3Coordinator.s3Bucket.value,
                    prefix = s3Coordinator.s3Prefix.value,
                    localSyncDirectory = "",
                ),
        )

    private val primaryState: StateFlow<PrimaryState> =
        combine(
            primaryConnectionState,
            s3Coordinator.s3LocalSyncDirectory,
        ) { primary, localSyncDirectory ->
            primary.copy(localSyncDirectory = localSyncDirectory)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                primaryConnectionState.value.copy(
                    localSyncDirectory = s3Coordinator.s3LocalSyncDirectory.value,
                ),
        )

    private val credentialState: StateFlow<CredentialState> =
        combine(
            s3Coordinator.accessKeyConfigured,
            s3Coordinator.secretAccessKeyConfigured,
            s3Coordinator.sessionTokenConfigured,
            s3Coordinator.encryptionPasswordConfigured,
            s3Coordinator.encryptionPassword2Configured,
        ) {
                accessKeyConfigured,
                secretAccessKeyConfigured,
                sessionTokenConfigured,
                encryptionPasswordConfigured,
                encryptionPassword2Configured,
            ->
            CredentialState(
                accessKeyConfigured = accessKeyConfigured,
                secretAccessKeyConfigured = secretAccessKeyConfigured,
                sessionTokenConfigured = sessionTokenConfigured,
                encryptionPasswordConfigured = encryptionPasswordConfigured,
                encryptionPassword2Configured = encryptionPassword2Configured,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                CredentialState(
                    accessKeyConfigured = s3Coordinator.accessKeyConfigured.value,
                    secretAccessKeyConfigured = s3Coordinator.secretAccessKeyConfigured.value,
                    sessionTokenConfigured = s3Coordinator.sessionTokenConfigured.value,
                    encryptionPasswordConfigured = s3Coordinator.encryptionPasswordConfigured.value,
                    encryptionPassword2Configured = s3Coordinator.encryptionPassword2Configured.value,
                ),
        )

    private val routingState: StateFlow<RoutingState> =
        combine(
            s3Coordinator.s3RcloneFilenameEncryption,
            s3Coordinator.s3RcloneFilenameEncoding,
            s3Coordinator.s3RcloneDirectoryNameEncryption,
            s3Coordinator.s3RcloneEncryptedSuffix,
        ) { filenameEncryption, filenameEncoding, directoryNameEncryption, encryptedSuffix ->
            RcloneFilenameState(
                filenameEncryption = filenameEncryption,
                filenameEncoding = filenameEncoding,
                directoryNameEncryption = directoryNameEncryption,
                encryptedSuffix = encryptedSuffix,
            )
        }.let { rcloneFilenameState ->
        combine(
            s3Coordinator.s3PathStyle,
            s3Coordinator.s3EncryptionMode,
            s3Coordinator.s3RcloneDataEncryptionEnabled,
            rcloneFilenameState,
        ) { pathStyle, encryptionMode, dataEncryptionEnabled, filenameState ->
            RoutingState(
                pathStyle = pathStyle,
                encryptionMode = encryptionMode,
                rcloneFilenameEncryption = filenameState.filenameEncryption,
                rcloneFilenameEncoding = filenameState.filenameEncoding,
                rcloneDirectoryNameEncryption = filenameState.directoryNameEncryption,
                rcloneDataEncryptionEnabled = dataEncryptionEnabled,
                rcloneEncryptedSuffix = filenameState.encryptedSuffix,
            )
        }
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                RoutingState(
                    pathStyle = s3Coordinator.s3PathStyle.value,
                    encryptionMode = s3Coordinator.s3EncryptionMode.value,
                    rcloneFilenameEncryption = s3Coordinator.s3RcloneFilenameEncryption.value,
                    rcloneFilenameEncoding = s3Coordinator.s3RcloneFilenameEncoding.value,
                    rcloneDirectoryNameEncryption = s3Coordinator.s3RcloneDirectoryNameEncryption.value,
                    rcloneDataEncryptionEnabled = s3Coordinator.s3RcloneDataEncryptionEnabled.value,
                    rcloneEncryptedSuffix = s3Coordinator.s3RcloneEncryptedSuffix.value,
                ),
        )

    private val identityState: StateFlow<IdentityState> =
        combine(
            primaryState,
            credentialState,
            routingState,
        ) { primary, credentials, routing ->
            IdentityState(
                enabled = primary.enabled,
                endpointUrl = primary.endpointUrl,
                region = primary.region,
                bucket = primary.bucket,
                prefix = primary.prefix,
                localSyncDirectory = primary.localSyncDirectory,
                accessKeyConfigured = credentials.accessKeyConfigured,
                secretAccessKeyConfigured = credentials.secretAccessKeyConfigured,
                sessionTokenConfigured = credentials.sessionTokenConfigured,
                pathStyle = routing.pathStyle,
                encryptionMode = routing.encryptionMode,
                encryptionPasswordConfigured = credentials.encryptionPasswordConfigured,
                encryptionPassword2Configured = credentials.encryptionPassword2Configured,
                rcloneFilenameEncryption = routing.rcloneFilenameEncryption,
                rcloneFilenameEncoding = routing.rcloneFilenameEncoding,
                rcloneDirectoryNameEncryption = routing.rcloneDirectoryNameEncryption,
                rcloneDataEncryptionEnabled = routing.rcloneDataEncryptionEnabled,
                rcloneEncryptedSuffix = routing.rcloneEncryptedSuffix,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                IdentityState(
                    enabled = primaryState.value.enabled,
                    endpointUrl = primaryState.value.endpointUrl,
                    region = primaryState.value.region,
                    bucket = primaryState.value.bucket,
                    prefix = primaryState.value.prefix,
                    localSyncDirectory = primaryState.value.localSyncDirectory,
                    accessKeyConfigured = credentialState.value.accessKeyConfigured,
                    secretAccessKeyConfigured = credentialState.value.secretAccessKeyConfigured,
                    sessionTokenConfigured = credentialState.value.sessionTokenConfigured,
                    pathStyle = routingState.value.pathStyle,
                    encryptionMode = routingState.value.encryptionMode,
                    encryptionPasswordConfigured = credentialState.value.encryptionPasswordConfigured,
                    encryptionPassword2Configured = credentialState.value.encryptionPassword2Configured,
                    rcloneFilenameEncryption = routingState.value.rcloneFilenameEncryption,
                    rcloneFilenameEncoding = routingState.value.rcloneFilenameEncoding,
                    rcloneDirectoryNameEncryption = routingState.value.rcloneDirectoryNameEncryption,
                    rcloneDataEncryptionEnabled = routingState.value.rcloneDataEncryptionEnabled,
                    rcloneEncryptedSuffix = routingState.value.rcloneEncryptedSuffix,
                ),
        )

    private val syncSettingsState: StateFlow<SyncSettingsState> =
        combine(
            s3Coordinator.s3AutoSyncEnabled,
            s3Coordinator.s3AutoSyncInterval,
            s3Coordinator.s3SyncOnRefreshEnabled,
            s3Coordinator.s3LastSyncTime,
            s3Coordinator.s3SyncState,
        ) { autoSyncEnabled, autoSyncInterval, syncOnRefreshEnabled, lastSyncTime, syncState ->
            SyncSettingsState(
                autoSyncEnabled = autoSyncEnabled,
                autoSyncInterval = autoSyncInterval,
                syncOnRefreshEnabled = syncOnRefreshEnabled,
                lastSyncTime = lastSyncTime,
                syncState = syncState,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                SyncSettingsState(
                    autoSyncEnabled = s3Coordinator.s3AutoSyncEnabled.value,
                    autoSyncInterval = s3Coordinator.s3AutoSyncInterval.value,
                    syncOnRefreshEnabled = s3Coordinator.s3SyncOnRefreshEnabled.value,
                    lastSyncTime = s3Coordinator.s3LastSyncTime.value,
                    syncState = s3Coordinator.s3SyncState.value,
                ),
        )

    val sectionState: StateFlow<S3SectionState> =
        combine(
            identityState,
            syncSettingsState,
            s3Coordinator.connectionTestState,
        ) { identity, syncSettings, connectionTestState ->
            S3SectionState(
                enabled = identity.enabled,
                endpointUrl = identity.endpointUrl,
                region = identity.region,
                bucket = identity.bucket,
                prefix = identity.prefix,
                localSyncDirectory = identity.localSyncDirectory,
                accessKeyConfigured = identity.accessKeyConfigured,
                secretAccessKeyConfigured = identity.secretAccessKeyConfigured,
                sessionTokenConfigured = identity.sessionTokenConfigured,
                pathStyle = identity.pathStyle,
                encryptionMode = identity.encryptionMode,
                encryptionPasswordConfigured = identity.encryptionPasswordConfigured,
                encryptionPassword2Configured = identity.encryptionPassword2Configured,
                rcloneFilenameEncryption = identity.rcloneFilenameEncryption,
                rcloneFilenameEncoding = identity.rcloneFilenameEncoding,
                rcloneDirectoryNameEncryption = identity.rcloneDirectoryNameEncryption,
                rcloneDataEncryptionEnabled = identity.rcloneDataEncryptionEnabled,
                rcloneEncryptedSuffix = identity.rcloneEncryptedSuffix,
                autoSyncEnabled = syncSettings.autoSyncEnabled,
                autoSyncInterval = syncSettings.autoSyncInterval,
                syncOnRefreshEnabled = syncSettings.syncOnRefreshEnabled,
                lastSyncTime = syncSettings.lastSyncTime,
                syncState = syncSettings.syncState,
                connectionTestState = connectionTestState,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                S3SectionState(
                    enabled = identityState.value.enabled,
                    endpointUrl = identityState.value.endpointUrl,
                    region = identityState.value.region,
                    bucket = identityState.value.bucket,
                    prefix = identityState.value.prefix,
                    localSyncDirectory = identityState.value.localSyncDirectory,
                    accessKeyConfigured = identityState.value.accessKeyConfigured,
                    secretAccessKeyConfigured = identityState.value.secretAccessKeyConfigured,
                    sessionTokenConfigured = identityState.value.sessionTokenConfigured,
                    pathStyle = identityState.value.pathStyle,
                    encryptionMode = identityState.value.encryptionMode,
                    encryptionPasswordConfigured = identityState.value.encryptionPasswordConfigured,
                    encryptionPassword2Configured = identityState.value.encryptionPassword2Configured,
                    rcloneFilenameEncryption = identityState.value.rcloneFilenameEncryption,
                    rcloneFilenameEncoding = identityState.value.rcloneFilenameEncoding,
                    rcloneDirectoryNameEncryption = identityState.value.rcloneDirectoryNameEncryption,
                    rcloneDataEncryptionEnabled = identityState.value.rcloneDataEncryptionEnabled,
                    rcloneEncryptedSuffix = identityState.value.rcloneEncryptedSuffix,
                    autoSyncEnabled = syncSettingsState.value.autoSyncEnabled,
                    autoSyncInterval = syncSettingsState.value.autoSyncInterval,
                    syncOnRefreshEnabled = syncSettingsState.value.syncOnRefreshEnabled,
                    lastSyncTime = syncSettingsState.value.lastSyncTime,
                    syncState = syncSettingsState.value.syncState,
                    connectionTestState = s3Coordinator.connectionTestState.value,
                ),
        )
}
