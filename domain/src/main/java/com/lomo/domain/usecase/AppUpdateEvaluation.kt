package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.LatestAppRelease

internal fun evaluateAppUpdate(
    release: LatestAppRelease,
    currentVersionName: String,
): AppUpdateInfo? {
    val remoteVersion = release.toAppUpdateInfo().version
    val localVersion = currentVersionName.substringBefore("-")
    val forceUpdate = release.body.contains(FORCE_UPDATE_MARKER)
    val updateAvailable =
        forceUpdate ||
            isRemoteVersionNewer(
                localVersion = localVersion,
                remoteVersion = remoteVersion,
            )
    if (!updateAvailable) {
        return null
    }
    return release.toAppUpdateInfo()
}

internal fun LatestAppRelease.toAppUpdateInfo(): AppUpdateInfo =
    AppUpdateInfo(
        url = htmlUrl,
        version = tagName.removePrefix("v"),
        releaseNotes = body,
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
    )

private fun isRemoteVersionNewer(
    localVersion: String,
    remoteVersion: String,
): Boolean =
    try {
        compareVersionParts(localVersion = localVersion, remoteVersion = remoteVersion) > 0
    } catch (_: Exception) {
        remoteVersion != localVersion
    }

private fun compareVersionParts(
    localVersion: String,
    remoteVersion: String,
): Int {
    val localParts = localVersion.split('.').map { it.toIntOrNull() ?: 0 }
    val remoteParts = remoteVersion.split('.').map { it.toIntOrNull() ?: 0 }
    val length = maxOf(localParts.size, remoteParts.size)
    for (index in 0 until length) {
        val localPart = localParts.getOrElse(index) { 0 }
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val comparison = remotePart.compareTo(localPart)
        if (comparison != 0) {
            return comparison
        }
    }
    return 0
}

internal const val FORCE_UPDATE_MARKER = "[FORCE_UPDATE]"
