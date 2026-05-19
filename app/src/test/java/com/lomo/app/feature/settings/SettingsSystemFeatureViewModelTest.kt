package com.lomo.app.feature.settings

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
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
 * - Capability: Settings system feature ViewModel, showing version name and manual updates.
 * - Scenarios:
 *   - Given a specific current version name, initialization exposes it in StateFlow.
 *   - Given an available update and check triggers, manual update state transitions correctly and suppresses duplicate requests.
 *   - Given no update available, manual update check transitions to UpToDate.
 *   - Given checker failure, manual update check transitions to Error state with message.
 *   - Given debug release preview trigger, opens preview dialog even if filter would hide it.
 * - Observable outcomes:
 *   - ViewModel StateFlow values (currentVersion, manualUpdateState, debugPreviewDialogState).
 *   - Number of repository manual update check invocations.
 * - TDD proof: Confirms version name resolution, checked/available update state machines, and concurrency gating.
 * - Excludes: Compose UI rendering, snackbar displaying.
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
                    apkDownloadUrl = "https://example.com/assets/lomo-1.0.0.apk",
                    apkFileName = "lomo-1.0.0.apk",
                    apkSizeBytes = 8_192L,
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
                    apkDownloadUrl = "https://example.com/assets/lomo-1.0.0.apk",
                    apkFileName = "lomo-1.0.0.apk",
                    apkSizeBytes = 8_192L,
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

    private class FakeAppRuntimeInfoRepository(val currentVersion: String) : AppRuntimeInfoRepository {
        override suspend fun getCurrentVersionName(): String = currentVersion
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
}
