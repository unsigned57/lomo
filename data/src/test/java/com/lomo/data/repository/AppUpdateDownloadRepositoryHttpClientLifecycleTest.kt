package com.lomo.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.lomo.data.R
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: AppUpdateDownloadRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: app update download orchestration uses a lifecycle-owned HTTP downloader that can recover after Android background cleanup closes the transport.
 *
 * Scenarios:
 * - Given install permission is granted and a downloader is injected, when update download starts, then the injected downloader receives the release URL and writes the target APK.
 * - Given the injected downloader emits progress, when repository states are collected, then progress is surfaced before downloaded APK verification.
 * - Given APK verification rejects the downloaded file, when post-download states are emitted, then installer launch is skipped.
 * - Given the update HTTP transport is closed after a download, when the same repository path downloads later, then it uses a rebuilt live client and still emits the expected states.
 *
 * Observable outcomes:
 * - Downloader input URL, downloaded file bytes observed by the verifier, rebuilt client request paths, and emitted AppUpdateInstallState sequence.
 *
 * TDD proof:
 * - Fails before the fix because an owner-created singleton downloader captures one raw HttpClient; after lifecycle close the repository path still uses the permanently closed client instead of a rebuilt client.
 *
 * Excludes:
 * - Ktor byte streaming behavior, PackageManager archive parsing, FileProvider URI generation, and package-installer UI.
 */
class AppUpdateDownloadRepositoryHttpClientLifecycleTest : DataFunSpec() {
    init {
        test("given injected downloader when download starts then repository uses the seam before apk verification") {
            runTest {
                val cacheDir = Files.createTempDirectory("lomo-update-cache").toFile()
                val context = updateContext(cacheDir = cacheDir)
                val downloader = FakeAppUpdateApkDownloader(payload = "fake-apk".encodeToByteArray())
                val verifier = RejectingVerifier(message = "Verifier saw injected payload")
                val installerResultObserver = RecordingInstallerResultObserver()
                val repository =
                    AppUpdateDownloadRepositoryImpl(
                        context = context,
                        downloader = downloader,
                        apkVerifier = verifier,
                        installerResultObserver = installerResultObserver,
                    )

                val states = repository.downloadAndInstall(updateInfo()).toList()

                assertSoftly {
                    downloader.downloadUrl shouldBe APK_URL
                    verifier.verifiedBytes.toList() shouldBe "fake-apk".encodeToByteArray().toList()
                    states shouldBe listOf(
                        AppUpdateInstallState.Preparing,
                        AppUpdateInstallState.Downloading(progress = 0),
                        AppUpdateInstallState.Downloading(progress = 42),
                        AppUpdateInstallState.Failed("Verifier saw injected payload"),
                    )
                    states.shouldContain(AppUpdateInstallState.Downloading(progress = 42))
                    installerResultObserver.awaitedMetadata shouldBe emptyList()
                }
            }
        }

        test("given lifecycle closed update transport when repository downloads later then rebuilt client is used") {
            runTest {
                val cacheDir = Files.createTempDirectory("lomo-update-cache-rebuilt").toFile()
                val context = updateContext(cacheDir = cacheDir)
                val requests = mutableListOf<String>()
                val clients = mutableListOf<HttpClient>()
                var nextClientId = 0
                val owner =
                    AppUpdateTransportOwner(
                        httpClientFactory = {
                            val clientId = nextClientId++
                            HttpClient(
                                MockEngine { request ->
                                    requests += "$clientId:${request.url.encodedPath}"
                                    respond(
                                        content = ByteReadChannel("apk-$clientId".encodeToByteArray()),
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentLength, "5"),
                                    )
                                },
                            ).also(clients::add)
                        },
                    )
                val repository =
                    AppUpdateDownloadRepositoryImpl(
                        context = context,
                        downloader = owner.createDownloader(),
                        apkVerifier = RejectingVerifier(message = "Verifier rejected downloaded APK"),
                        installerResultObserver = RecordingInstallerResultObserver(),
                    )

                val firstStates = repository.downloadAndInstall(updateInfo()).toList()
                owner.closeUpdateTransport()
                val secondStates = repository.downloadAndInstall(updateInfo()).toList()

                assertSoftly {
                    clients.size shouldBe 2
                    requests shouldBe listOf("0:/assets/lomo-v1.6.0-vc44.apk", "1:/assets/lomo-v1.6.0-vc44.apk")
                    firstStates shouldBe listOf(
                        AppUpdateInstallState.Preparing,
                        AppUpdateInstallState.Downloading(progress = 0),
                        AppUpdateInstallState.Downloading(progress = 100),
                        AppUpdateInstallState.Failed("Verifier rejected downloaded APK"),
                    )
                    secondStates shouldBe firstStates
                }
            }
        }
    }
}

private class FakeAppUpdateApkDownloader(
    private val payload: ByteArray,
) : AppUpdateApkDownloader {
    var downloadUrl: String? = null
        private set

    override suspend fun download(
        downloadUrl: String,
        outputFile: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        this.downloadUrl = downloadUrl
        onProgress(0)
        outputFile.writeBytes(payload)
        onProgress(42)
    }
}

private class RejectingVerifier(
    private val message: String,
) : AppUpdateApkVerifier {
    lateinit var verifiedBytes: ByteArray
        private set

    override fun verify(
        apkFile: File,
        updateInfo: AppUpdateInfo,
    ): DownloadedApkVerificationResult {
        verifiedBytes = apkFile.readBytes()
        return DownloadedApkVerificationResult.Invalid(message)
    }
}

private class RecordingInstallerResultObserver : AppUpdateInstallerResultObserver {
    val awaitedMetadata = mutableListOf<VerifiedAppUpdatePackageMetadata>()

    override suspend fun awaitInstallerResult(
        verifiedDownloadedApk: VerifiedAppUpdatePackageMetadata,
        updateInfo: AppUpdateInfo,
    ): AppUpdateInstallerResult {
        awaitedMetadata += verifiedDownloadedApk
        return AppUpdateInstallerResult.Installed
    }
}

private fun updateContext(cacheDir: File): Context {
    val context = mockk<Context>()
    val packageManager = mockk<PackageManager>()
    every { context.cacheDir } returns cacheDir
    every { context.packageManager } returns packageManager
    every { context.getString(R.string.app_update_enable_installs) } returns "Enable installs"
    every { context.getString(R.string.app_update_empty_file) } returns "Empty APK"
    every { packageManager.canRequestPackageInstalls() } returns true
    return context
}

private fun updateInfo(): AppUpdateInfo =
    AppUpdateInfo(
        url = "https://example.com/releases/1.6.0",
        version = "1.6.0",
        releaseNotes = "Release notes",
        apkDownloadUrl = APK_URL,
        apkFileName = "lomo-v1.6.0-vc44.apk",
        apkSizeBytes = 4_096L,
        expectedPackageName = "com.lomo.app",
        expectedVersionName = "1.6.0",
        expectedVersionCode = 44L,
    )

private const val APK_URL = "https://example.com/assets/lomo-v1.6.0-vc44.apk"
