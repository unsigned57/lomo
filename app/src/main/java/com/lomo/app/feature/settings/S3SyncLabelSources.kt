package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

internal data class S3SyncLabelSources(
    val syncIntervals: ImmutableMap<String, String>,
    val pathStyles: ImmutableMap<S3PathStyle, String>,
    val encryptionModes: ImmutableMap<S3EncryptionMode, String>,
    val rcloneFilenameEncryptions: ImmutableMap<S3RcloneFilenameEncryption, String>,
    val rcloneFilenameEncodings: ImmutableMap<S3RcloneFilenameEncoding, String>,
)

@Composable
internal fun rememberS3SyncLabelSources(
    options: SettingsDialogOptions,
    syncIntervalLabels: ImmutableMap<String, String>,
): S3SyncLabelSources {
    val pathStyleLabels = remember(options.s3PathStyleLabels) {
        options.s3PathStyleLabels.toImmutableMap()
    }
    val encryptionModeLabels = remember(options.s3EncryptionModeLabels) {
        options.s3EncryptionModeLabels.toImmutableMap()
    }
    val rcloneFilenameEncryptionLabels = remember(options.s3RcloneFilenameEncryptionLabels) {
        options.s3RcloneFilenameEncryptionLabels.toImmutableMap()
    }
    val rcloneFilenameEncodingLabels = remember(options.s3RcloneFilenameEncodingLabels) {
        options.s3RcloneFilenameEncodingLabels.toImmutableMap()
    }
    return remember(
        syncIntervalLabels,
        pathStyleLabels,
        encryptionModeLabels,
        rcloneFilenameEncryptionLabels,
        rcloneFilenameEncodingLabels,
    ) {
        S3SyncLabelSources(
            syncIntervals = syncIntervalLabels,
            pathStyles = pathStyleLabels,
            encryptionModes = encryptionModeLabels,
            rcloneFilenameEncryptions = rcloneFilenameEncryptionLabels,
            rcloneFilenameEncodings = rcloneFilenameEncodingLabels,
        )
    }
}
