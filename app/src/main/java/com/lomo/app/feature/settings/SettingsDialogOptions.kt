package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider

data class SettingsDialogOptions(
    val dateFormats: List<String>,
    val timeFormats: List<String>,
    val themeModes: List<ThemeMode>,
    val filenameFormats: List<String>,
    val timestampFormats: List<String>,
    val gitSyncIntervals: List<String>,
    val snapshotRetentionCounts: List<Int>,
    val snapshotRetentionDays: List<Int>,
    val webDavProviders: List<WebDavProvider>,
    val s3PathStyles: List<S3PathStyle>,
    val s3EncryptionModes: List<S3EncryptionMode>,
    val s3RcloneFilenameEncryptions: List<S3RcloneFilenameEncryption>,
    val s3RcloneFilenameEncodings: List<S3RcloneFilenameEncoding>,
    val languageTag: String,
    val languageLabels: Map<String, String>,
    val themeModeLabels: Map<ThemeMode, String>,
    val gitSyncIntervalLabels: Map<String, String>,
    val webDavProviderLabels: Map<WebDavProvider, String>,
    val s3PathStyleLabels: Map<S3PathStyle, String>,
    val s3EncryptionModeLabels: Map<S3EncryptionMode, String>,
    val s3RcloneFilenameEncryptionLabels: Map<S3RcloneFilenameEncryption, String>,
    val s3RcloneFilenameEncodingLabels: Map<S3RcloneFilenameEncoding, String>,
)

@Composable
fun SettingsDialogHost(
    uiState: SettingsScreenUiState,
    features: SettingsFeatures,
    dialogState: SettingsDialogState,
    options: SettingsDialogOptions,
    onApplyLanguageTag: (String) -> Unit,
) {
    DisplayPreferenceDialogs(
        uiState = uiState,
        displayFeature = features.display,
        dialogState = dialogState,
        options = options,
        onApplyLanguageTag = onApplyLanguageTag,
    )
    StoragePreferenceDialogs(
        uiState = uiState,
        storageFeature = features.storage,
        dialogState = dialogState,
        options = options,
    )
    ShareCardPreferenceDialogs(
        shareCardFeature = features.shareCard,
        dialogState = dialogState,
    )
    SnapshotPreferenceDialogs(
        uiState = uiState,
        features = features,
        dialogState = dialogState,
        options = options,
    )
    LanShareDialogs(
        uiState = uiState,
        features = features,
        dialogState = dialogState,
    )
    GitDialogs(
        uiState = uiState,
        features = features,
        dialogState = dialogState,
        options = options,
    )
    WebDavDialogs(
        uiState = uiState,
        features = features,
        dialogState = dialogState,
        options = options,
    )
    S3Dialogs(
        uiState = uiState,
        features = features,
        dialogState = dialogState,
        options = options,
    )
}
