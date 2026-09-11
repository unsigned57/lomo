package com.lomo.domain.model

data class LatestAppRelease(
    val tagName: String,
    val htmlUrl: String,
    val body: String,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
    val assetCandidates: List<AppUpdateAssetCandidate> = emptyList(),
)

data class AppUpdateAssetCandidate(
    val fileName: String,
    val downloadUrl: String?,
    val sizeBytes: Long? = null,
    val verification: AppUpdateAssetVerification,
)

sealed interface AppUpdateAssetVerification {
    data class Verified(
        val packageName: String,
        val versionName: String,
        val versionCode: Long? = null,
    ) : AppUpdateAssetVerification

    data class Unsupported(
        val reason: AppUpdateAssetUnsupportedReason,
    ) : AppUpdateAssetVerification
}

enum class AppUpdateAssetUnsupportedReason {
    DOWNLOAD_URL_MISSING,
    METADATA_UNAVAILABLE,
    WRONG_PACKAGE,
    VERSION_MISMATCH,
}

data class AppUpdateInfo(
    val url: String,
    val version: String,
    val releaseNotes: String,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
    val expectedPackageName: String? = null,
    val expectedVersionName: String? = null,
    val expectedVersionCode: Long? = null,
)
