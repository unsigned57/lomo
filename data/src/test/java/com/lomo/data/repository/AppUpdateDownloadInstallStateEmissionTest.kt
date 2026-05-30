package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateInstallState
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: gateInstallPermissionBeforeDownload and emitInstallStatesAfterDownload
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: in-app updates gate downloads on install permission, verify downloaded APK policy before launching the installer, and wait for installer-result proof before reporting success.
 *
 * Scenarios:
 * - Given install permission is missing, when download orchestration starts, then recovery state is emitted and download work is skipped.
 * - Given install permission is granted, when download orchestration starts, then download work can emit progress.
 * - Given downloaded APK verification fails, when post-download install states are emitted, then failure is emitted and installer launch is skipped.
 * - Given downloaded APK verification succeeds, when the system installer is launched, then waiting-for-installer-result is emitted before any terminal success.
 * - Given installer-result observation proves the expected update is installed, when post-download install states are emitted, then success is reported.
 *
 * Observable outcomes:
 * - Emitted AppUpdateInstallState sequence, guarded download/installer callback invocation, and installer-result observation ordering.
 *
 * TDD proof:
 * - Fails before the fix because post-download state emission reports Completed immediately after launching the system installer instead of waiting for installer-result proof.
 *
 * Excludes:
 * - APK network transport bytes, Android PackageManager manifest extraction, FileProvider URI generation, and actual PackageInstaller UI.
 */
class AppUpdateDownloadInstallStateEmissionTest : DataFunSpec() {
    init {
        test("gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work") { `gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work`() }

        test("gateInstallPermissionBeforeDownload allows download work when install permission is already granted") { `gateInstallPermissionBeforeDownload allows download work when install permission is already granted`() }

        test("emitInstallStatesAfterDownload emits failure and skips installer when apk verification fails") {
            `emitInstallStatesAfterDownload emits failure and skips installer when apk verification fails`()
        }

        test("emitInstallStatesAfterDownload waits for installer result before reporting completed") {
            `emitInstallStatesAfterDownload waits for installer result before reporting completed`()
        }
    }


    private fun `gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work`() =
        runTest {
            var downloadStarted = false

            val states =
                flow {
                    gateInstallPermissionBeforeDownload(
                        canRequestPackageInstalls = false,
                        missingPermissionMessage = "Allow installs from this app to continue.",
                    ) {
                        downloadStarted = true
                    }
                }.toList()

            states shouldBe listOf(
                    AppUpdateInstallState.RequiresInstallPermission(
                        message = "Allow installs from this app to continue.",
                    ),
                )
            (downloadStarted).shouldBeFalse()
        }

    private fun `gateInstallPermissionBeforeDownload allows download work when install permission is already granted`() =
        runTest {
            var downloadStarts = 0

            val states =
                flow {
                    gateInstallPermissionBeforeDownload(
                        canRequestPackageInstalls = true,
                        missingPermissionMessage = "unused",
                    ) {
                        downloadStarts += 1
                        emit(AppUpdateInstallState.Preparing)
                        emit(AppUpdateInstallState.Downloading(progress = 1))
                    }
                }.toList()

            states shouldBe listOf(
                    AppUpdateInstallState.Preparing,
                    AppUpdateInstallState.Downloading(progress = 1),
                )
            downloadStarts shouldBe 1
            (downloadStarts > 0).shouldBeTrue()
        }

    private fun `emitInstallStatesAfterDownload emits failure and skips installer when apk verification fails`() =
        runTest {
            var installerLaunched = false

            val states =
                flow {
                    emitInstallStatesAfterDownload(
                        verifyDownloadedApk = {
                            DownloadedApkVerificationResult.Invalid(
                                message = "Downloaded APK does not match this update.",
                            )
                        },
                        awaitInstallerResult = { error("installer result must not be awaited after verification failure") },
                    ) {
                        installerLaunched = true
                    }
                }.toList()

            states shouldBe listOf(
                AppUpdateInstallState.Failed("Downloaded APK does not match this update."),
            )
            installerLaunched.shouldBeFalse()
        }

    private fun `emitInstallStatesAfterDownload waits for installer result before reporting completed`() =
        runTest {
            val events = mutableListOf<String>()

            val states =
                flow {
                    emitInstallStatesAfterDownload(
                        verifyDownloadedApk = { DownloadedApkVerificationResult.Valid(verifiedUpdateMetadata()) },
                        awaitInstallerResult = {
                            events += "await-result"
                            AppUpdateInstallerResult.Installed
                        },
                    ) {
                        events += "launch-installer"
                    }
                }.toList()

            states shouldBe listOf(
                AppUpdateInstallState.Installing,
                AppUpdateInstallState.WaitingForInstallerResult,
                AppUpdateInstallState.Completed,
            )
            events shouldBe listOf(
                "launch-installer",
                "await-result",
            )
        }
}

private fun verifiedUpdateMetadata(): VerifiedAppUpdatePackageMetadata =
    VerifiedAppUpdatePackageMetadata(
        packageName = "com.lomo.app",
        versionName = "1.6.0",
        versionCode = 44L,
        signerCertificateSha256Digests = setOf("release-signer"),
    )
