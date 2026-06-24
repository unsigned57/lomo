package com.lomo.data.repository

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.lomo.data.R
import com.lomo.domain.model.AppUpdateInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal interface AppUpdateApkVerifier {
    fun verify(
        apkFile: File,
        updateInfo: AppUpdateInfo,
    ): DownloadedApkVerificationResult
}

internal interface AppUpdateInstallerResultObserver {
    suspend fun awaitInstallerResult(
        verifiedDownloadedApk: VerifiedAppUpdatePackageMetadata,
        updateInfo: AppUpdateInfo,
    ): AppUpdateInstallerResult
}

internal class PackageManagerAppUpdateApkVerifier(
    private val context: android.content.Context,
) : AppUpdateApkVerifier,
    AppUpdateInstallerResultObserver {
    override fun verify(
        apkFile: File,
        updateInfo: AppUpdateInfo,
    ): DownloadedApkVerificationResult =
        verifyDownloadedApkMetadata(
            metadata = readArchiveMetadata(apkFile),
            installedSignerDigests = readInstalledSignerDigests(),
            updateInfo = updateInfo,
            invalidApkMessage = context.getString(R.string.app_update_invalid_apk),
            metadataUnavailableMessage = context.getString(R.string.app_update_missing_apk_metadata),
            mismatchMessage = context.getString(R.string.app_update_apk_metadata_mismatch),
        )

    override suspend fun awaitInstallerResult(
        verifiedDownloadedApk: VerifiedAppUpdatePackageMetadata,
        updateInfo: AppUpdateInfo,
    ): AppUpdateInstallerResult {
        val failureMessage = context.getString(R.string.app_update_install_not_completed)
        val installed =
            withTimeoutOrNull(INSTALLER_RESULT_TIMEOUT_MS) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (installedPackageMatchesVerifiedUpdate(
                            installedMetadata = readInstalledMetadata(verifiedDownloadedApk.packageName),
                            verifiedDownloadedApk = verifiedDownloadedApk,
                            updateInfo = updateInfo,
                        )
                    ) {
                        return@withTimeoutOrNull true
                    }
                    delay(INSTALLER_RESULT_POLL_INTERVAL_MS)
                }
                false
            }
        return when (installed) {
            true -> AppUpdateInstallerResult.Installed
            false,
            null,
            -> AppUpdateInstallerResult.Failed(failureMessage)
        }
    }

    private fun readArchiveMetadata(apkFile: File): DownloadedApkMetadata? {
        val packageInfo = archivePackageInfo(apkFile) ?: return null
        return packageInfo.toDownloadedApkMetadata()
    }

    private fun archivePackageInfo(apkFile: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(packageSigningInfoFlags().toLong()),
            )
        } else {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, packageSigningInfoFlags())
        }

    private fun readInstalledSignerDigests(): Set<String> =
        when (val metadata = readInstalledMetadata(context.packageName)) {
            null -> emptySet()
            else -> metadata.signerCertificateSha256Digests
        }

    private fun readInstalledMetadata(packageName: String): DownloadedApkMetadata? {
        val packageInfo = installedPackageInfo(packageName) ?: return null
        return packageInfo.toDownloadedApkMetadata()
    }

    private fun installedPackageInfo(packageName: String): PackageInfo? =
        runCatching { installedPackageInfoOrThrow(packageName) }
            .onFailure { error ->
                // behavior-contract: silent-result-ok: NameNotFoundException signals "not installed"; rethrow else
                if (error !is PackageManager.NameNotFoundException) throw error
            }
            .getOrNull()

    private fun installedPackageInfoOrThrow(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(packageSigningInfoFlags().toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(packageName, packageSigningInfoFlags())
        }
}

private const val INSTALLER_RESULT_TIMEOUT_MS = 10 * 60 * 1000L
private const val INSTALLER_RESULT_POLL_INTERVAL_MS = 1_000L
