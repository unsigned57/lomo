package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateAssetCandidate
import com.lomo.domain.model.AppUpdateAssetVerification
import com.lomo.domain.model.LatestAppRelease

internal fun evaluateAppUpdate(
    release: LatestAppRelease,
    currentVersionName: String,
    currentVersionCode: Long?,
): AppUpdateInfo? {
    val remoteVersion = release.versionName()
    val installableCandidate = release.verifiedInstallableCandidate(remoteVersion) ?: return null
    val verified = installableCandidate.verification as AppUpdateAssetVerification.Verified
    val localVersion = currentVersionName.substringBefore("-")
    val updateAvailable = isCandidateNewer(verified, localVersion, currentVersionCode)
    if (!updateAvailable) {
        return null
    }
    return release.toAppUpdateInfo(installableCandidate)
}

internal fun LatestAppRelease.toAppUpdateInfo(): AppUpdateInfo =
    toAppUpdateInfo(verifiedInstallableCandidate(versionName()))

private fun LatestAppRelease.toAppUpdateInfo(
    installableCandidate: AppUpdateAssetCandidate?,
): AppUpdateInfo =
    AppUpdateInfo(
        url = htmlUrl,
        version = versionName(),
        releaseNotes = body,
        apkDownloadUrl = installableCandidate?.downloadUrl,
        apkFileName = installableCandidate?.fileName,
        apkSizeBytes = installableCandidate?.sizeBytes,
        expectedPackageName = installableCandidate?.verifiedOrNull()?.packageName,
        expectedVersionName = installableCandidate?.verifiedOrNull()?.versionName?.let(::normalizeVersion),
        expectedVersionCode = installableCandidate?.verifiedOrNull()?.versionCode,
    )

private fun LatestAppRelease.versionName(): String = tagName.removePrefix("v")

private fun LatestAppRelease.verifiedInstallableCandidate(remoteVersion: String): AppUpdateAssetCandidate? =
    assetCandidates.firstOrNull { candidate ->
        val verified = candidate.verification as? AppUpdateAssetVerification.Verified ?: return@firstOrNull false
        candidate.fileName.endsWith(".apk", ignoreCase = true) &&
            !candidate.downloadUrl.isNullOrBlank() &&
            normalizeVersion(verified.versionName) == normalizeVersion(remoteVersion)
    }

private fun AppUpdateAssetCandidate.verifiedOrNull(): AppUpdateAssetVerification.Verified? =
    verification as? AppUpdateAssetVerification.Verified

private fun isCandidateNewer(
    verified: AppUpdateAssetVerification.Verified,
    localVersion: String,
    currentVersionCode: Long?,
): Boolean {
    val candidateVersionCode = verified.versionCode
    if (currentVersionCode != null) {
        return candidateVersionCode != null && candidateVersionCode > currentVersionCode
    }
    return isRemoteVersionNewer(
        localVersion = localVersion,
        remoteVersion = normalizeVersion(verified.versionName),
    )
}

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

private fun normalizeVersion(version: String): String = version.removePrefix("v")
