package com.lomo.domain.model

data class AppUpdateInstallAttempt(
    val updateInfo: AppUpdateInfo,
    val phase: AppUpdateInstallPhase,
    val progress: Int? = null,
    val permissionMessage: String? = null,
    val downloadedFilePath: String? = null,
    val verifiedPackageMetadata: AppUpdateVerifiedPackageMetadata? = null,
    val installerOutcome: AppUpdateInstallerOutcome? = null,
    val failureMessage: String? = null,
)

enum class AppUpdateInstallPhase {
    Recorded,
    WaitingForInstallPermission,
    Preparing,
    Downloading,
    Downloaded,
    Installing,
    WaitingForInstallerResult,
    Completed,
    Failed,
    Cancelled,
}

data class AppUpdateVerifiedPackageMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long?,
    val signerCertificateSha256Digests: Set<String>,
)

sealed interface AppUpdateInstallerOutcome {
    data object Installed : AppUpdateInstallerOutcome

    data class Failed(
        val message: String,
    ) : AppUpdateInstallerOutcome
}
