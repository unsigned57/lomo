package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.domain.model.AppUpdateAssetCandidate
import com.lomo.domain.model.AppUpdateAssetVerification
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import com.lomo.domain.usecase.GetLatestAppReleaseUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SettingsSystemFeatureViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Settings system update controls expose only verified installable updates while preserving version and debug preview state.
 *
 * Scenarios:
 * - Given a specific current version name, when the feature initializes, then StateFlow exposes that version.
 * - Given a verified newer APK candidate, when a manual update check triggers, then manual update state transitions correctly and suppresses duplicate requests.
 * - Given no update available, when a manual update check runs, then state becomes UpToDate.
 * - Given checker failure, when a manual update check runs, then state becomes Error with a message.
 * - Given a debug release preview trigger, when preview is opened, then the dialog appears even if normal version filtering would hide it.
 *
 * Observable outcomes:
 * - ViewModel StateFlow values for currentVersion, manualUpdateState, and debugPreviewDialogState.
 * - Number of repository manual update check invocations.
 * - Dialog metadata including release URL, version, release notes, APK download URL, file name, and size.
 *
 * TDD proof:
 * - RED observed with `./kotlin test --include-classes='com.lomo.app.feature.settings.SettingsSystemFeatureViewModelTest'`.
 * - The old fixtures failed at SettingsSystemFeatureViewModelTest.kt:128 and :213 because releases without verified asset candidates now resolve to UpToDate or a non-downloadable preview.
 *
 * Excludes:
 * - Compose UI rendering, snackbar displaying, GitHub HTTP transport, APK bytes, and PackageInstaller behavior.
 *
 * Test Change Justification:
 * Reason category: Contract hardening for update safety.
 * Old behavior/assertion being replaced: Release fixtures used legacy apkDownloadUrl fields without typed asset verification.
 * Why old assertion is no longer correct: app update dialogs must be driven by domain-vetted installable candidates, not by unverified release-level APK fields.
 * Coverage preserved by: manual update state, duplicate suppression, debug preview, release notes, and APK metadata assertions.
 * Why this is not fitting the test to the implementation: the fixtures now encode the product contract that in-app installation requires a verified candidate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSystemFeatureViewModelTest : AppFunSpec() {
    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    init {
        test("initialization loads current version name") {
            runTest {
                val fixture = systemFeatureFixture(
                    currentVersion = "0.9.1",
                    appUpdateRepository = FakeAppUpdateRepository(null)
                )

                val feature = SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

                advanceUntilIdle()

                feature.currentVersion.value shouldBe "0.9.1"
                feature.manualUpdateState.value shouldBe SettingsManualUpdateState.Idle
            }
        }

        test("manual update check exposes inline update state and suppresses duplicate requests") {
            runTest(StandardTestDispatcher()) {
                val gate = CompletableDeferred<Unit>()
                var manualChecks = 0
                val fakeAppUpdateRepository = object : AppUpdateRepository {
                    override suspend fun fetchLatestRelease(): LatestAppRelease {
                        manualChecks += 1
                        gate.await()
                        return LatestAppRelease(
                            tagName = "v1.0.0",
                            htmlUrl = "https://example.com/releases/1.0.0",
                            body = "notes",
                            assetCandidates =
                                listOf(
                                    verifiedCandidate(
                                        fileName = "lomo-v1.0.0.apk",
                                        downloadUrl = "https://example.com/assets/lomo-v1.0.0.apk",
                                        sizeBytes = 4_096L,
                                        versionName = "1.0.0",
                                    ),
                                ),
                        )
                    }
                }

                val fixture = systemFeatureFixture(
                    currentVersion = "0.9.1",
                    checkUpdatesOnStartupEnabled = false,
                    appUpdateRepository = fakeAppUpdateRepository
                )

                val feature = SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

                feature.checkForUpdatesManually()
                runCurrent()

                feature.manualUpdateState.value shouldBe SettingsManualUpdateState.Checking

                feature.checkForUpdatesManually()
                runCurrent()

                manualChecks shouldBe 1

                gate.complete(Unit)
                runCurrent()

                feature.manualUpdateState.value shouldBe SettingsManualUpdateState.UpdateAvailable(
                    dialogState = AppUpdateDialogState(
                        url = "https://example.com/releases/1.0.0",
                        version = "1.0.0",
                        releaseNotes = "notes",
                        apkDownloadUrl = "https://example.com/assets/lomo-v1.0.0.apk",
                        apkFileName = "lomo-v1.0.0.apk",
                        apkSizeBytes = 4_096L,
                        expectedPackageName = "com.lomo.app",
                        expectedVersionName = "1.0.0",
                    ),
                )
            }
        }

        test("manual update check exposes up-to-date state when no release is available") {
            runTest {
                val fixture = systemFeatureFixture(
                    currentVersion = "0.9.1",
                    checkUpdatesOnStartupEnabled = true,
                    appUpdateRepository = FakeAppUpdateRepository(null)
                )

                val feature = SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

                feature.checkForUpdatesManually()
                advanceUntilIdle()

                feature.manualUpdateState.value shouldBe SettingsManualUpdateState.UpToDate
            }
        }

        test("manual update check exposes error state when checker throws") {
            runTest {
                val fakeAppUpdateRepository = object : AppUpdateRepository {
                    override suspend fun fetchLatestRelease(): LatestAppRelease? {
                        throw IllegalStateException("network down")
                    }
                }
                val fixture = systemFeatureFixture(
                    currentVersion = "0.9.1",
                    checkUpdatesOnStartupEnabled = true,
                    appUpdateRepository = fakeAppUpdateRepository
                )

                val feature = SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

                feature.checkForUpdatesManually()
                advanceUntilIdle()

                feature.manualUpdateState.value shouldBe SettingsManualUpdateState.Error("network down")
            }
        }

        test("debug latest release preview exposes a real release dialog even when normal version filtering would hide it") {
            runTest {
                val latestRelease = LatestAppRelease(
                    tagName = "v1.0.0",
                    htmlUrl = "https://example.com/releases/1.0.0",
                    body = "[FORCE_UPDATE]\npreview notes",
                    assetCandidates =
                        listOf(
                            verifiedCandidate(
                                fileName = "lomo-v1.0.0.apk",
                                downloadUrl = "https://example.com/assets/lomo-v1.0.0.apk",
                                sizeBytes = 8_192L,
                                versionName = "1.0.0",
                            ),
                        ),
                )
                val fixture = systemFeatureFixture(
                    currentVersion = "1.0.0-DEBUG",
                    checkUpdatesOnStartupEnabled = true,
                    appUpdateRepository = FakeAppUpdateRepository(latestRelease)
                )

                val feature = SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

                feature.openDebugLatestReleasePreview()
                advanceUntilIdle()

                feature.debugPreviewDialogState.value shouldBe AppUpdateDialogState(
                    url = "https://example.com/releases/1.0.0",
                    version = "1.0.0",
                    releaseNotes = "preview notes",
                    apkDownloadUrl = "https://example.com/assets/lomo-v1.0.0.apk",
                    apkFileName = "lomo-v1.0.0.apk",
                    apkSizeBytes = 8_192L,
                    expectedPackageName = "com.lomo.app",
                    expectedVersionName = "1.0.0",
                )
            }
        }
    }

    private data class SystemFeatureFixture(
        val appConfigCoordinator: SettingsAppConfigCoordinator,
        val appUpdateChecker: AppUpdateChecker,
        val getCurrentAppVersionUseCase: GetCurrentAppVersionUseCase,
    )

    private class FakeAppUpdateRepository(var latestRelease: LatestAppRelease?) : AppUpdateRepository {
        override suspend fun fetchLatestRelease(): LatestAppRelease? = latestRelease
    }

    private class FakeAppRuntimeInfoRepository(
        val currentVersion: String,
        val currentVersionCode: Long? = null,
    ) : AppRuntimeInfoRepository {
        override suspend fun getCurrentVersionName(): String = currentVersion

        override suspend fun getCurrentVersionCode(): Long? = currentVersionCode
    }

    private fun TestScope.systemFeatureFixture(
        currentVersion: String,
        checkUpdatesOnStartupEnabled: Boolean = false,
        appUpdateRepository: AppUpdateRepository,
    ): SystemFeatureFixture {
        val appConfigRepository = FakeAppConfigRepository()
        kotlinx.coroutines.runBlocking {
            appConfigRepository.setCheckUpdatesOnStartup(checkUpdatesOnStartupEnabled)
        }
        val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, FakeWorkspaceStateResolver())
        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository(currentVersion)

        return SystemFeatureFixture(
            appConfigCoordinator = SettingsAppConfigCoordinator(
                appConfigRepository = appConfigRepository,
                switchRootStorageUseCase = switchRootStorageUseCase,
                scope = backgroundScope,
                customFontStore = FakeCustomFontStore(),
            ),
            appUpdateChecker = AppUpdateChecker(
                checkAppUpdateUseCase = CheckAppUpdateUseCase(
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ),
                checkStartupAppUpdateUseCase = CheckStartupAppUpdateUseCase(
                    preferencesRepository = appConfigRepository,
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ),
                getLatestAppReleaseUseCase = GetLatestAppReleaseUseCase(
                    appUpdateRepository = appUpdateRepository,
                ),
            ),
            getCurrentAppVersionUseCase = GetCurrentAppVersionUseCase(
                appRuntimeInfoRepository = appRuntimeInfoRepository,
            ),
        )
    }

    private fun verifiedCandidate(
        fileName: String,
        downloadUrl: String,
        sizeBytes: Long,
        versionName: String,
    ): AppUpdateAssetCandidate =
        AppUpdateAssetCandidate(
            fileName = fileName,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes,
            verification =
                AppUpdateAssetVerification.Verified(
                    packageName = "com.lomo.app",
                    versionName = versionName,
                ),
        )
}
