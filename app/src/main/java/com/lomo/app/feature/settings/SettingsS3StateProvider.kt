package com.lomo.app.feature.settings

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private data class S3ExtensionState(
    val endpointUrl: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val localSyncDirectory: String,
)

private data class S3RoutingState(
    val pathStyle: S3PathStyle,
    val encryptionMode: S3EncryptionMode,
    val rcloneFilenameEncryption: S3RcloneFilenameEncryption,
    val rcloneFilenameEncoding: S3RcloneFilenameEncoding,
    val rcloneDirectoryNameEncryption: Boolean,
    val rcloneDataEncryptionEnabled: Boolean,
    val rcloneEncryptedSuffix: String,
)

private data class S3RcloneFilenameState(
    val filenameEncryption: S3RcloneFilenameEncryption,
    val filenameEncoding: S3RcloneFilenameEncoding,
    val directoryNameEncryption: Boolean,
    val encryptedSuffix: String,
)

class SettingsS3StateProvider(
    private val s3Coordinator: SettingsS3Coordinator,
    scope: CoroutineScope,
) {
    val connectionTestState: StateFlow<RemoteProviderConnectionTestState> = s3Coordinator.connectionTestState

    private val baseExtensionState: StateFlow<S3ExtensionState> =
        combine(
            s3Coordinator.s3EndpointUrl,
            s3Coordinator.s3Region,
            s3Coordinator.s3Bucket,
            s3Coordinator.s3Prefix,
        ) { endpointUrl, region, bucket, prefix ->
            S3ExtensionState(
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
                S3ExtensionState(
                    endpointUrl = s3Coordinator.s3EndpointUrl.value,
                    region = s3Coordinator.s3Region.value,
                    bucket = s3Coordinator.s3Bucket.value,
                    prefix = s3Coordinator.s3Prefix.value,
                    localSyncDirectory = "",
                ),
        )

    private val extensionState: StateFlow<S3ExtensionState> =
        combine(
            baseExtensionState,
            s3Coordinator.s3LocalSyncDirectory,
        ) { extension, localSyncDirectory ->
            extension.copy(localSyncDirectory = localSyncDirectory)
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                baseExtensionState.value.copy(
                    localSyncDirectory = s3Coordinator.s3LocalSyncDirectory.value,
                ),
        )

    private val routingState: StateFlow<S3RoutingState> =
        combine(
            s3Coordinator.s3RcloneFilenameEncryption,
            s3Coordinator.s3RcloneFilenameEncoding,
            s3Coordinator.s3RcloneDirectoryNameEncryption,
            s3Coordinator.s3RcloneEncryptedSuffix,
        ) { filenameEncryption, filenameEncoding, directoryNameEncryption, encryptedSuffix ->
            S3RcloneFilenameState(
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
                S3RoutingState(
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
                S3RoutingState(
                    pathStyle = s3Coordinator.s3PathStyle.value,
                    encryptionMode = s3Coordinator.s3EncryptionMode.value,
                    rcloneFilenameEncryption = s3Coordinator.s3RcloneFilenameEncryption.value,
                    rcloneFilenameEncoding = s3Coordinator.s3RcloneFilenameEncoding.value,
                    rcloneDirectoryNameEncryption = s3Coordinator.s3RcloneDirectoryNameEncryption.value,
                    rcloneDataEncryptionEnabled = s3Coordinator.s3RcloneDataEncryptionEnabled.value,
                    rcloneEncryptedSuffix = s3Coordinator.s3RcloneEncryptedSuffix.value,
                ),
        )

    val sectionState: StateFlow<S3SectionState> =
        combine(
            s3Coordinator.providerSettingsModel,
            extensionState,
            routingState,
        ) { providerSettings, extension, routing ->
            providerSettings.toS3SectionState(
                extension = extension,
                routing = routing,
            )
        }.stateIn(
            scope = scope,
            started = settingsWhileSubscribed(),
            initialValue =
                s3Coordinator.providerSettingsModel.value.toS3SectionState(
                    extension = extensionState.value,
                    routing = routingState.value,
                ),
        )
}

private fun RemoteProviderSettingsModel.toS3SectionState(
    extension: S3ExtensionState,
    routing: S3RoutingState,
): S3SectionState =
    S3SectionState(
        providerSettings = this,
        endpointUrl = extension.endpointUrl,
        region = extension.region,
        bucket = extension.bucket,
        prefix = extension.prefix,
        localSyncDirectory = extension.localSyncDirectory,
        pathStyle = routing.pathStyle,
        encryptionMode = routing.encryptionMode,
        rcloneFilenameEncryption = routing.rcloneFilenameEncryption,
        rcloneFilenameEncoding = routing.rcloneFilenameEncoding,
        rcloneDirectoryNameEncryption = routing.rcloneDirectoryNameEncryption,
        rcloneDataEncryptionEnabled = routing.rcloneDataEncryptionEnabled,
        rcloneEncryptedSuffix = routing.rcloneEncryptedSuffix,
    )
