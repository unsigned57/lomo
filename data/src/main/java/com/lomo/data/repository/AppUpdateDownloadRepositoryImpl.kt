package com.lomo.data.repository

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.lomo.data.R
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.repository.AppUpdateDownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateDownloadRepositoryImpl
    @Inject
    internal constructor(
        @ApplicationContext private val context: android.content.Context,
        private val downloader: AppUpdateApkDownloader,
        private val apkVerifier: AppUpdateApkVerifier,
        private val installerResultObserver: AppUpdateInstallerResultObserver,
    ) : AppUpdateDownloadRepository {
        @Volatile
        private var currentDownloadJob: Job? = null

        override fun downloadAndInstall(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState> =
            flow {
                try {
                    currentDownloadJob = currentCoroutineContext()[Job]
                    val downloadUrl =
                        updateInfo.apkDownloadUrl?.takeIf { it.isNotBlank() }
                            ?: run {
                                emit(AppUpdateInstallState.Failed(context.getString(R.string.app_update_missing_apk)))
                                return@flow
                            }

                    gateInstallPermissionBeforeDownload(
                        canRequestPackageInstalls = context.packageManager.canRequestPackageInstalls(),
                        missingPermissionMessage = context.getString(R.string.app_update_enable_installs),
                    ) {
                        emit(AppUpdateInstallState.Preparing)
                        val targetFile =
                            downloadApk(
                                downloadUrl = downloadUrl,
                                fileName = updateInfo.apkFileName ?: defaultApkName(updateInfo.version),
                            ) { progress ->
                                emit(AppUpdateInstallState.Downloading(progress))
                            }

                        emitInstallStatesAfterDownload(
                            verifyDownloadedApk = { apkVerifier.verify(targetFile, updateInfo) },
                            awaitInstallerResult = { verifiedDownloadedApk ->
                                installerResultObserver.awaitInstallerResult(
                                    verifiedDownloadedApk = verifiedDownloadedApk,
                                    updateInfo = updateInfo,
                                )
                            },
                        ) {
                            launchInstaller(targetFile)
                        }
                    }
                } finally {
                    currentDownloadJob = null
                }
            }.flowOn(Dispatchers.IO)

        override fun cancelCurrentDownload() {
            currentDownloadJob?.cancel()
        }

        private suspend fun downloadApk(
            downloadUrl: String,
            fileName: String,
            onProgress: suspend (Int) -> Unit,
        ): File {
            currentCoroutineContext().ensureActive()
            val outputDir = File(context.cacheDir, UPDATE_DOWNLOAD_DIRECTORY).apply { mkdirs() }
            val outputFile = File(outputDir, sanitizeFileName(fileName))
            outputFile.delete()

            try {
                downloader.download(
                    outputFile = outputFile,
                    downloadUrl = downloadUrl,
                    onProgress = onProgress,
                )

                if (outputFile.length() <= 0L) {
                    throw IOException(context.getString(R.string.app_update_empty_file))
                }
                return outputFile
            } catch (error: AppUpdateDownloadHttpException) {
                throw IOException(context.getString(R.string.app_update_http_failed, error.statusCode), error)
            }
        }

        private fun launchInstaller(apkFile: File) {
            val apkUri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            try {
                context.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                throw IllegalStateException(context.getString(R.string.app_update_no_installer), error)
            }
        }

        private fun sanitizeFileName(rawName: String): String =
            rawName
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
                .ifBlank { "lomo-update.apk" }

        private fun defaultApkName(version: String): String =
            "lomo-v${version.ifBlank { "latest" }}.apk"

        private companion object {
            const val UPDATE_DOWNLOAD_DIRECTORY = "updates"
            const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        }
    }

internal suspend fun FlowCollector<AppUpdateInstallState>.gateInstallPermissionBeforeDownload(
    canRequestPackageInstalls: Boolean,
    missingPermissionMessage: String,
    onPermissionGranted: suspend FlowCollector<AppUpdateInstallState>.() -> Unit,
) {
    if (!canRequestPackageInstalls) {
        emit(AppUpdateInstallState.RequiresInstallPermission(message = missingPermissionMessage))
        return
    }

    onPermissionGranted()
}

internal suspend fun FlowCollector<AppUpdateInstallState>.emitInstallStatesAfterDownload(
    verifyDownloadedApk: () -> DownloadedApkVerificationResult,
    awaitInstallerResult: suspend (VerifiedAppUpdatePackageMetadata) -> AppUpdateInstallerResult,
    launchInstaller: () -> Unit,
) {
    when (val verification = verifyDownloadedApk()) {
        is DownloadedApkVerificationResult.Invalid -> {
            emit(AppUpdateInstallState.Failed(verification.message))
            return
        }

        is DownloadedApkVerificationResult.Valid -> {
            emit(AppUpdateInstallState.Installing)
            launchInstaller()
            emit(AppUpdateInstallState.WaitingForInstallerResult)
            when (val installerResult = awaitInstallerResult(verification.metadata)) {
                AppUpdateInstallerResult.Installed -> emit(AppUpdateInstallState.Completed)
                is AppUpdateInstallerResult.Failed -> emit(AppUpdateInstallState.Failed(installerResult.message))
            }
        }
    }
}

internal sealed interface DownloadedApkVerificationResult {
    data class Valid(
        val metadata: VerifiedAppUpdatePackageMetadata,
    ) : DownloadedApkVerificationResult

    data class Invalid(
        val message: String,
    ) : DownloadedApkVerificationResult
}

internal sealed interface AppUpdateInstallerResult {
    data object Installed : AppUpdateInstallerResult

    data class Failed(
        val message: String,
    ) : AppUpdateInstallerResult
}

internal data class VerifiedAppUpdatePackageMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long?,
    val signerCertificateSha256Digests: Set<String>,
)

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

internal data class DownloadedApkMetadata(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val signerCertificateSha256Digests: Set<String>,
)

internal object AppUpdateApkSignerPolicy {
    fun isSignedByInstalledAppSigner(
        archiveSignerDigests: Set<String>,
        installedSignerDigests: Set<String>,
    ): Boolean =
        archiveSignerDigests.isNotEmpty() &&
            installedSignerDigests.isNotEmpty() &&
            archiveSignerDigests == installedSignerDigests
}

internal fun verifyDownloadedApkMetadata(
    metadata: DownloadedApkMetadata?,
    installedSignerDigests: Set<String>,
    updateInfo: AppUpdateInfo,
    invalidApkMessage: String,
    metadataUnavailableMessage: String,
    mismatchMessage: String,
): DownloadedApkVerificationResult {
    metadata ?: return DownloadedApkVerificationResult.Invalid(invalidApkMessage)
    val expectedPackageName =
        updateInfo.expectedPackageName?.takeIf { it.isNotBlank() }
            ?: return DownloadedApkVerificationResult.Invalid(metadataUnavailableMessage)
    val expectedVersionName =
        updateInfo.expectedVersionName?.takeIf { it.isNotBlank() }
            ?: return DownloadedApkVerificationResult.Invalid(metadataUnavailableMessage)

    val mismatch =
        firstApkMetadataMismatch(
            metadata = metadata,
            expectedPackageName = expectedPackageName,
            expectedVersionName = expectedVersionName,
            expectedVersionCode = updateInfo.expectedVersionCode,
            installedSignerDigests = installedSignerDigests,
            mismatchMessage = mismatchMessage,
        )
    return mismatch
        ?: DownloadedApkVerificationResult.Valid(
            metadata =
                VerifiedAppUpdatePackageMetadata(
                    packageName = expectedPackageName,
                    versionName = metadata.versionName.orEmpty(),
                    versionCode = metadata.versionCode,
                    signerCertificateSha256Digests = metadata.signerCertificateSha256Digests,
                ),
        )
}

private fun firstApkMetadataMismatch(
    metadata: DownloadedApkMetadata,
    expectedPackageName: String,
    expectedVersionName: String,
    expectedVersionCode: Long?,
    installedSignerDigests: Set<String>,
    mismatchMessage: String,
): DownloadedApkVerificationResult.Invalid? {
    if (metadata.packageName != expectedPackageName) {
        return DownloadedApkVerificationResult.Invalid(mismatchMessage)
    }
    if (normalizeApkVersionName(metadata.versionName) != normalizeApkVersionName(expectedVersionName)) {
        return DownloadedApkVerificationResult.Invalid(mismatchMessage)
    }
    if (expectedVersionCode != null && metadata.versionCode != expectedVersionCode) {
        return DownloadedApkVerificationResult.Invalid(mismatchMessage)
    }
    val signerMatches =
        AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
            archiveSignerDigests = metadata.signerCertificateSha256Digests,
            installedSignerDigests = installedSignerDigests,
        )
    return if (!signerMatches) DownloadedApkVerificationResult.Invalid(mismatchMessage) else null
}

internal fun installedPackageMatchesVerifiedUpdate(
    installedMetadata: DownloadedApkMetadata?,
    verifiedDownloadedApk: VerifiedAppUpdatePackageMetadata,
    updateInfo: AppUpdateInfo,
): Boolean {
    installedMetadata ?: return false
    if (installedMetadata.packageName != verifiedDownloadedApk.packageName) {
        return false
    }
    val installedNormalized = normalizeApkVersionName(installedMetadata.versionName)
    val verifiedNormalized = normalizeApkVersionName(verifiedDownloadedApk.versionName)
    if (installedNormalized != verifiedNormalized) {
        return false
    }
    val expectedVersionCode = updateInfo.expectedVersionCode
    if (expectedVersionCode != null && installedMetadata.versionCode != expectedVersionCode) {
        return false
    }
    if (!AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
            archiveSignerDigests = installedMetadata.signerCertificateSha256Digests,
            installedSignerDigests = verifiedDownloadedApk.signerCertificateSha256Digests,
        )
    ) {
        return false
    }
    return true
}

private fun packageSigningInfoFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        GET_SIGNATURES_COMPAT
    }

private fun PackageInfo.signerCertificateSha256Digests(): Set<String> {
    val signatures =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            legacySignatures()
        }
    return signatures.mapTo(mutableSetOf()) { signature -> signature.sha256DigestHex() }
}

private fun PackageInfo.toDownloadedApkMetadata(): DownloadedApkMetadata =
    DownloadedApkMetadata(
        packageName = packageName,
        versionName = versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        signerCertificateSha256Digests = signerCertificateSha256Digests(),
    )

private fun PackageInfo.legacySignatures(): List<Signature> =
    // behavior-contract: silent-result-ok: reflection on legacy field may fail; empty list lets caller use signingInfo
    runCatching {
        val rawSignatures = PackageInfo::class.java.getField("signatures").get(this) as? Array<*>
        rawSignatures.orEmpty().filterIsInstance<Signature>()
    }.getOrDefault(emptyList())

private fun Signature.sha256DigestHex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString(separator = "") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and UNSIGNED_BYTE_MASK)
        }

private fun normalizeApkVersionName(versionName: String?): String = versionName.orEmpty().removePrefix("v")

private const val GET_SIGNATURES_COMPAT = 0x00000040
private const val UNSIGNED_BYTE_MASK = 0xff
private const val INSTALLER_RESULT_TIMEOUT_MS = 10 * 60 * 1000L
private const val INSTALLER_RESULT_POLL_INTERVAL_MS = 1_000L
