package com.lomo.domain.model

data class LatestAppRelease(
    val tagName: String,
    val htmlUrl: String,
    val body: String,
)

data class AppUpdateInfo(
    val url: String,
    val version: String,
    val releaseNotes: String,
)
