package com.lomo.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.lomo.data.R
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateInstallPhase
import com.lomo.domain.model.AppUpdateInstallState
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: AppUpdateDownloadRepositoryImpl persistent install attempt process
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: in-app update install attempts are persisted as a trusted state machine before permission, download, installer, or terminal work can proceed.
 *
 * Scenarios:
 * - Given unknown-source install permission is missing, when an update install is requested, then the persisted attempt records the update identity and permission-waiting recovery phase before returning to the app.
 * - Given the repository is recreated after a permission-waiting attempt, when the attempt is observed, then the same recovery phase and update metadata are restored from durable state.
 * - Given a downloaded APK verifies successfully, when the installer is launched and terminal result is awaited, then verified APK metadata and file path are persisted before installer wait/completion state.
 * - Given download fails after an install attempt is persisted, when downloadAndInstall exits with the original failure, then the durable attempt is terminal Failed with update identity, APK metadata, and failure evidence.
 * - Given installer launch fails after verified APK metadata is persisted, when downloadAndInstall exits with the original failure, then the durable attempt is terminal Failed and retains the verified APK metadata instead of remaining Installing.
 * - Given cancellation occurs during download orchestration, when downloadAndInstall exits, then cancellation is propagated and is not converted into an ordinary terminal Failed attempt.
 *
 * Observable outcomes:
 * - Emitted AppUpdateInstallState values, propagated failure/cancellation, and persisted AppUpdateInstallAttempt phase, update identity, APK file metadata, verified package metadata, installer outcome, and failure evidence.
 *
 * TDD proof:
 * - Fails before the fix because download and installer-launch exceptions leave the stored attempt in Preparing/Installing instead of terminal Failed.
 *
 * Excludes:
 * - GitHub release discovery, HTTP streaming internals, Android PackageManager archive parsing, FileProvider URI permissions, and actual PackageInstaller UI.
 */
class AppUpdateInstallAttemptPersistenceTest : DataFunSpec() {
    init {
        test("given missing install permission when repository is recreated then permission-waiting attempt metadata is restored") {
            runTest {
                val stateFile = Files.createTempFile("lomo-update-attempt", ".json").toFile()
                val context = updateContext(cacheDir = Files.createTempDirectory("lomo-update-cache").toFile(), canInstall = false)
                val firstRepository =
                    repository(
                        context = context,
                        stateStore = JsonFileAppUpdateInstallAttemptStore(stateFile),
                    )

                val states = firstRepository.downloadAndInstall(updateInfo()).toList()
                val recreatedRepository =
                    repository(
                        context = context,
                        stateStore = JsonFileAppUpdateInstallAttemptStore(stateFile),
                    )
                val restored = recreatedRepository.observeInstallAttempt().first()

                assertSoftly {
                    states shouldBe listOf(
                        AppUpdateInstallState.RequiresInstallPermission("Enable installs"),
                    )
                    restored?.phase shouldBe AppUpdateInstallPhase.WaitingForInstallPermission
                    restored?.updateInfo shouldBe updateInfo()
                    restored?.downloadedFilePath shouldBe null
                    restored?.verifiedPackageMetadata shouldBe null
                }
            }
        }

        test("given verified apk when installer wait starts then verified metadata is persisted before terminal completion") {
            runTest {
                val stateFile = Files.createTempFile("lomo-update-verified-attempt", ".json").toFile()
                val cacheDir = Files.createTempDirectory("lomo-update-cache-verified").toFile()
                val context = updateContext(cacheDir = cacheDir, canInstall = true)
                val stateStore = JsonFileAppUpdateInstallAttemptStore(stateFile)
                val installerResultObserver =
                    PersistenceRecordingInstallerResultObserver(
                        onAwait = {
                            val persisted = stateStore.observe().first()
                            assertSoftly {
                                persisted?.phase shouldBe AppUpdateInstallPhase.WaitingForInstallerResult
                                persisted?.downloadedFilePath shouldBe File(cacheDir, "updates/lomo-v1.6.0-vc44.apk").absolutePath
                                persisted?.verifiedPackageMetadata?.packageName shouldBe "com.lomo.app"
                                persisted?.verifiedPackageMetadata?.versionName shouldBe "1.6.0"
                                persisted?.verifiedPackageMetadata?.versionCode shouldBe 44L
                            }
                        },
                    )
                val repository =
                    repository(
                        context = context,
                        stateStore = stateStore,
                        verifier = AcceptingVerifier,
                        installerResultObserver = installerResultObserver,
                    )

                val states = repository.downloadAndInstall(updateInfo()).toList()
                val terminal = stateStore.observe().first()

                assertSoftly {
                    states shouldBe listOf(
                        AppUpdateInstallState.Preparing,
                        AppUpdateInstallState.Downloading(progress = 100),
                        AppUpdateInstallState.Installing,
                        AppUpdateInstallState.WaitingForInstallerResult,
                        AppUpdateInstallState.Completed,
                    )
                    terminal?.phase shouldBe AppUpdateInstallPhase.Completed
                    terminal?.installerOutcome shouldBe com.lomo.domain.model.AppUpdateInstallerOutcome.Installed
                }
            }
        }

        test("given download fails after attempt is persisted when flow exits then terminal failed attempt keeps update evidence") {
            runTest {
                val stateFile = Files.createTempFile("lomo-update-download-failed-attempt", ".json").toFile()
                val context = updateContext(cacheDir = Files.createTempDirectory("lomo-update-cache-failed").toFile(), canInstall = true)
                val stateStore = JsonFileAppUpdateInstallAttemptStore(stateFile)
                val downloadFailure = IOException("network unavailable")
                val repository =
                    repository(
                        context = context,
                        stateStore = stateStore,
                        downloader = ThrowingDownloader(downloadFailure),
                    )

                val failure =
                    shouldThrow<IOException> {
                        repository.downloadAndInstall(updateInfo()).toList()
                    }
                val terminal = requireNotNull(stateStore.observe().first())

                assertSoftly {
                    failure shouldBe downloadFailure
                    terminal.phase shouldBe AppUpdateInstallPhase.Failed
                    terminal.updateInfo shouldBe updateInfo()
                    terminal.progress shouldBe null
                    terminal.downloadedFilePath shouldBe null
                    terminal.verifiedPackageMetadata shouldBe null
                    terminal.installerOutcome shouldBe null
                    terminal.failureMessage shouldBe "network unavailable"
                }
            }
        }

        test("given installer launch fails after verified metadata when flow exits then failed attempt retains apk metadata") {
            runTest {
                val stateFile = Files.createTempFile("lomo-update-launch-failed-attempt", ".json").toFile()
                val cacheDir = Files.createTempDirectory("lomo-update-cache-launch-failed").toFile()
                val context = updateContext(cacheDir = cacheDir, canInstall = true)
                val stateStore = JsonFileAppUpdateInstallAttemptStore(stateFile)
                val launchFailure = IllegalStateException("installer unavailable")
                val repository =
                    repository(
                        context = context,
                        stateStore = stateStore,
                        verifier = AcceptingVerifier,
                        installerLauncher = ThrowingInstallerLauncher(launchFailure),
                    )

                val failure =
                    shouldThrow<IllegalStateException> {
                        repository.downloadAndInstall(updateInfo()).toList()
                    }
                val terminal = requireNotNull(stateStore.observe().first())

                assertSoftly {
                    failure shouldBe launchFailure
                    terminal.phase shouldBe AppUpdateInstallPhase.Failed
                    terminal.updateInfo shouldBe updateInfo()
                    terminal.downloadedFilePath shouldBe File(cacheDir, "updates/lomo-v1.6.0-vc44.apk").absolutePath
                    terminal.verifiedPackageMetadata?.packageName shouldBe "com.lomo.app"
                    terminal.verifiedPackageMetadata?.versionName shouldBe "1.6.0"
                    terminal.verifiedPackageMetadata?.versionCode shouldBe 44L
                    terminal.installerOutcome shouldBe null
                    terminal.failureMessage shouldBe "installer unavailable"
                }
            }
        }

        test("given cancellation during download when flow exits then cancellation is propagated without failed attempt conversion") {
            runTest {
                val stateFile = Files.createTempFile("lomo-update-cancelled-attempt", ".json").toFile()
                val context = updateContext(cacheDir = Files.createTempDirectory("lomo-update-cache-cancelled").toFile(), canInstall = true)
                val stateStore = JsonFileAppUpdateInstallAttemptStore(stateFile)
                val cancellation = CancellationException("collector cancelled")
                val repository =
                    repository(
                        context = context,
                        stateStore = stateStore,
                        downloader = ThrowingDownloader(cancellation),
                    )

                val failure =
                    shouldThrow<CancellationException> {
                        repository.downloadAndInstall(updateInfo()).toList()
                    }
                val persisted = stateStore.observe().first()

                assertSoftly {
                    failure shouldBe cancellation
                    persisted?.phase shouldBe AppUpdateInstallPhase.Preparing
                    persisted?.failureMessage shouldBe null
                    persisted?.installerOutcome shouldBe null
                }
            }
        }
    }
}

private fun repository(
    context: Context,
    stateStore: AppUpdateInstallAttemptStore,
    downloader: AppUpdateApkDownloader = WritingDownloader,
    verifier: AppUpdateApkVerifier = PersistenceRejectingVerifier(message = "unused"),
    installerResultObserver: AppUpdateInstallerResultObserver = PersistenceRecordingInstallerResultObserver(),
    installerLauncher: AppUpdateInstallerLauncher = RecordingInstallerLauncher,
): AppUpdateDownloadRepositoryImpl =
    AppUpdateDownloadRepositoryImpl(
        context = context,
        downloader = downloader,
        apkVerifier = verifier,
        installerResultObserver = installerResultObserver,
        attemptStore = stateStore,
        installerLauncher = installerLauncher,
    )

private object WritingDownloader : AppUpdateApkDownloader {
    override suspend fun download(
        downloadUrl: String,
        outputFile: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        outputFile.writeText("fake-apk")
        onProgress(100)
    }
}

private class ThrowingDownloader(
    private val failure: Throwable,
) : AppUpdateApkDownloader {
    override suspend fun download(
        downloadUrl: String,
        outputFile: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        throw failure
    }
}

private object AcceptingVerifier : AppUpdateApkVerifier {
    override fun verify(
        apkFile: File,
        updateInfo: com.lomo.domain.model.AppUpdateInfo,
    ): DownloadedApkVerificationResult =
        DownloadedApkVerificationResult.Valid(
            VerifiedAppUpdatePackageMetadata(
                packageName = "com.lomo.app",
                versionName = "1.6.0",
                versionCode = 44L,
                signerCertificateSha256Digests = setOf("release-signer"),
            ),
        )
}

private class PersistenceRejectingVerifier(
    private val message: String,
) : AppUpdateApkVerifier {
    override fun verify(
        apkFile: File,
        updateInfo: com.lomo.domain.model.AppUpdateInfo,
    ): DownloadedApkVerificationResult = DownloadedApkVerificationResult.Invalid(message)
}

private object RecordingInstallerLauncher : AppUpdateInstallerLauncher {
    val launchedFiles = mutableListOf<File>()

    override fun launch(apkFile: File) {
        launchedFiles += apkFile
    }
}

private class ThrowingInstallerLauncher(
    private val failure: Throwable,
) : AppUpdateInstallerLauncher {
    override fun launch(apkFile: File) {
        throw failure
    }
}

private class PersistenceRecordingInstallerResultObserver(
    private val onAwait: suspend () -> Unit = {},
) : AppUpdateInstallerResultObserver {
    override suspend fun awaitInstallerResult(
        verifiedDownloadedApk: VerifiedAppUpdatePackageMetadata,
        updateInfo: com.lomo.domain.model.AppUpdateInfo,
    ): AppUpdateInstallerResult {
        onAwait()
        return AppUpdateInstallerResult.Installed
    }
}

private fun updateContext(
    cacheDir: File,
    canInstall: Boolean,
): Context {
    val context = mockk<Context>()
    val packageManager = mockk<PackageManager>()
    every { context.cacheDir } returns cacheDir
    every { context.packageManager } returns packageManager
    every { context.packageName } returns "com.lomo.app"
    every { context.getString(R.string.app_update_enable_installs) } returns "Enable installs"
    every { context.getString(R.string.app_update_missing_apk) } returns "Missing APK"
    every { context.getString(R.string.app_update_empty_file) } returns "Empty APK"
    every { packageManager.canRequestPackageInstalls() } returns canInstall
    every { context.startActivity(any()) } returns Unit
    return context
}

private fun updateInfo(): com.lomo.domain.model.AppUpdateInfo =
    com.lomo.domain.model.AppUpdateInfo(
        url = "https://example.com/releases/1.6.0",
        version = "1.6.0",
        releaseNotes = "Release notes",
        apkDownloadUrl = "https://example.com/assets/lomo-v1.6.0-vc44.apk",
        apkFileName = "lomo-v1.6.0-vc44.apk",
        apkSizeBytes = 4_096L,
        expectedPackageName = "com.lomo.app",
        expectedVersionName = "1.6.0",
        expectedVersionCode = 44L,
    )
