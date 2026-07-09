package com.lomo.data.repository

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateVerifiedPackageMetadata
import java.security.MessageDigest
import java.util.Locale

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

internal fun VerifiedAppUpdatePackageMetadata.toDomainMetadata(): AppUpdateVerifiedPackageMetadata =
    AppUpdateVerifiedPackageMetadata(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        signerCertificateSha256Digests = signerCertificateSha256Digests,
    )

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
    return AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
        archiveSignerDigests = installedMetadata.signerCertificateSha256Digests,
        installedSignerDigests = verifiedDownloadedApk.signerCertificateSha256Digests,
    )
}

internal fun packageSigningInfoFlags(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        GET_SIGNATURES_COMPAT
    }

internal fun PackageInfo.toDownloadedApkMetadata(): DownloadedApkMetadata =
    DownloadedApkMetadata(
        packageName = packageName,
        versionName = versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        signerCertificateSha256Digests = signerCertificateSha256Digests(),
    )

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

private fun PackageInfo.signerCertificateSha256Digests(): Set<String> {
    val signatures =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            legacySignatures()
        }
    return signatures.mapTo(mutableSetOf()) { signature -> signature.sha256DigestHex() }
}

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
