package com.lomo.domain.model

data class LatestAppRelease(
    val tagName: String,
    val htmlUrl: String,
    val body: String,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
)

data class AppUpdateInfo(
    val url: String,
    val version: String,
    val releaseNotes: String,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
)
