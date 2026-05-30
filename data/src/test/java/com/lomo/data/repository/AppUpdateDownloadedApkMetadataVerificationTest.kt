package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateInfo
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: verifyDownloadedApkMetadata and AppUpdateApkSignerPolicy
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: downloaded app update APKs are rejected before installer launch unless manifest metadata and release signer match the expected installed app update.
 *
 * Scenarios:
 * - Given an APK whose package name differs from the expected update, when metadata verification runs, then installation is rejected.
 * - Given an APK whose versionName differs from the expected update, when metadata verification runs, then installation is rejected.
 * - Given an APK whose versionCode differs from the expected update, when metadata verification runs, then installation is rejected.
 * - Given update metadata is missing required expected package or versionName values, when metadata verification runs, then installation is rejected.
 * - Given the archive signer differs from the installed app signer, when metadata verification runs, then installation is rejected.
 * - Given the archive or installed signer cannot be read, when metadata verification runs, then installation is rejected.
 * - Given package name, versionName, versionCode, and signer digests all match, when metadata verification runs, then the APK is accepted.
 *
 * Observable outcomes:
 * - DownloadedApkVerificationResult.Valid or Invalid with the configured failure message.
 *
 * TDD proof:
 * - RED observed with `./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.AppUpdateDownloadedApkMetadataVerificationTest'`.
 * - The new tests failed to compile because downloaded APK verification had no installed signer input, signer metadata, or signer comparison policy.
 *
 * Excludes:
 * - Android PackageManager archive parsing flags, certificate byte extraction, FileProvider URI generation, and actual PackageInstaller UI.
 */
class AppUpdateDownloadedApkMetadataVerificationTest : DataFunSpec() {
    init {
        test("given wrong package metadata when verifying downloaded apk then install is rejected") {
            val result =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata().copy(packageName = "com.example.other"),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            result.shouldBeInvalid(MISMATCH_MESSAGE)
        }

        test("given wrong version name metadata when verifying downloaded apk then install is rejected") {
            val result =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata().copy(versionName = "1.5.0"),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            result.shouldBeInvalid(MISMATCH_MESSAGE)
        }

        test("given wrong version code metadata when verifying downloaded apk then install is rejected") {
            val result =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata().copy(versionCode = 43L),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            result.shouldBeInvalid(MISMATCH_MESSAGE)
        }

        test("given missing expected package or version metadata when verifying downloaded apk then install is rejected") {
            val missingPackage =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata(),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(expectedPackageName = null),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )
            val missingVersionName =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata(),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(expectedVersionName = null),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            assertSoftly {
                missingPackage.shouldBeInvalid(METADATA_UNAVAILABLE_MESSAGE)
                missingVersionName.shouldBeInvalid(METADATA_UNAVAILABLE_MESSAGE)
            }
        }

        test("given signer mismatch when verifying downloaded apk then install is rejected") {
            val result =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata(signerDigests = setOf("debug-signer")),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            result.shouldBeInvalid(MISMATCH_MESSAGE)
        }

        test("given missing archive or installed signer when verifying downloaded apk then install is rejected") {
            val missingArchiveSigner =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata(signerDigests = emptySet()),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )
            val missingInstalledSigner =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata(),
                    installedSignerDigests = emptySet(),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            assertSoftly {
                missingArchiveSigner.shouldBeInvalid(MISMATCH_MESSAGE)
                missingInstalledSigner.shouldBeInvalid(MISMATCH_MESSAGE)
            }
        }

        test("given matching metadata and signer when verifying downloaded apk then install is accepted") {
            val result =
                verifyDownloadedApkMetadata(
                    metadata = matchingMetadata(),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    updateInfo = expectedUpdateInfo(),
                    invalidApkMessage = INVALID_APK_MESSAGE,
                    metadataUnavailableMessage = METADATA_UNAVAILABLE_MESSAGE,
                    mismatchMessage = MISMATCH_MESSAGE,
                )

            val valid = result.shouldBeInstanceOf<DownloadedApkVerificationResult.Valid>()
            valid.metadata shouldBe VerifiedAppUpdatePackageMetadata(
                packageName = "com.lomo.app",
                versionName = "1.6.0",
                versionCode = 44L,
                signerCertificateSha256Digests = setOf(RELEASE_SIGNER_DIGEST),
            )
        }

        test("signer policy accepts only non-empty matching digest sets") {
            assertSoftly {
                AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
                    archiveSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                ) shouldBe true
                AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
                    archiveSignerDigests = setOf("debug-signer"),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                ) shouldBe false
                AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
                    archiveSignerDigests = emptySet(),
                    installedSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                ) shouldBe false
                AppUpdateApkSignerPolicy.isSignedByInstalledAppSigner(
                    archiveSignerDigests = setOf(RELEASE_SIGNER_DIGEST),
                    installedSignerDigests = emptySet(),
                ) shouldBe false
            }
        }
    }
}

private fun DownloadedApkVerificationResult.shouldBeInvalid(message: String) {
    val invalid = shouldBeInstanceOf<DownloadedApkVerificationResult.Invalid>()
    invalid.message shouldBe message
}

private fun matchingMetadata(
    signerDigests: Set<String> = setOf(RELEASE_SIGNER_DIGEST),
): DownloadedApkMetadata =
    DownloadedApkMetadata(
        packageName = "com.lomo.app",
        versionName = "1.6.0",
        versionCode = 44L,
        signerCertificateSha256Digests = signerDigests,
    )

private fun expectedUpdateInfo(
    expectedPackageName: String? = "com.lomo.app",
    expectedVersionName: String? = "1.6.0",
    expectedVersionCode: Long? = 44L,
): AppUpdateInfo =
    AppUpdateInfo(
        url = "https://example.com/releases/1.6.0",
        version = "1.6.0",
        releaseNotes = "Release notes",
        apkDownloadUrl = "https://example.com/assets/lomo-v1.6.0-vc44.apk",
        apkFileName = "lomo-v1.6.0-vc44.apk",
        apkSizeBytes = 4_096L,
        expectedPackageName = expectedPackageName,
        expectedVersionName = expectedVersionName,
        expectedVersionCode = expectedVersionCode,
    )

private const val RELEASE_SIGNER_DIGEST = "release-signer"
private const val INVALID_APK_MESSAGE = "Invalid APK"
private const val METADATA_UNAVAILABLE_MESSAGE = "Missing APK metadata"
private const val MISMATCH_MESSAGE = "APK metadata mismatch"
