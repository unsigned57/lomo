package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsSystemFeatureViewModel
 * - Behavior focus: current-version loading, inline manual update state transitions, error classification, and in-flight re-entry protection.
 * - Observable outcomes: exposed currentVersion and manualUpdateState values.
 * - Red phase: Fails in test-only scope setup when SettingsAppConfigCoordinator sharing jobs stay attached to the main runTest scope and trigger UncompletedCoroutinesError before the locked behavior assertions can complete.
 * - Excludes: Compose rendering, snackbar presentation, and repository transport details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSystemFeatureViewModelTest {
    @Test
    fun `initialization loads current version name`() =
        runTest {
            val fixture = systemFeatureFixture(currentVersion = "0.9.1")

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

            advanceUntilIdle()

            assertEquals("0.9.1", feature.currentVersion.value)
            assertEquals(SettingsManualUpdateState.Idle, feature.manualUpdateState.value)
        }

    @Test
    fun `manual update check exposes inline update state and suppresses duplicate requests`() =
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var manualChecks = 0
            val fixture =
                systemFeatureFixture(
                    currentVersion = "0.9.1",
                    checkUpdatesOnStartupEnabled = false,
                ).also { createdFixture ->
                    coEvery { createdFixture.appUpdateRepository.fetchLatestRelease() } coAnswers {
                        manualChecks += 1
                        gate.await()
                        LatestAppRelease(
                            tagName = "v1.0.0",
                            htmlUrl = "https://example.com/releases/1.0.0",
                            body = "notes",
                        )
                    }
                }

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

            feature.checkForUpdatesManually()
            runCurrent()

            assertEquals(SettingsManualUpdateState.Checking, feature.manualUpdateState.value)

            feature.checkForUpdatesManually()
            runCurrent()

            assertEquals(1, manualChecks)

            gate.complete(Unit)
            runCurrent()

            assertEquals(
                SettingsManualUpdateState.UpdateAvailable(
                    dialogState =
                        AppUpdateDialogState(
                            url = "https://example.com/releases/1.0.0",
                            version = "1.0.0",
                            releaseNotes = "notes",
                        ),
                ),
                feature.manualUpdateState.value,
            )
        }

    @Test
    fun `manual update check exposes up-to-date state when no release is available`() =
        runTest {
            val fixture =
                systemFeatureFixture(
                    currentVersion = "0.9.1",
                    checkUpdatesOnStartupEnabled = true,
                )
            coEvery { fixture.appUpdateRepository.fetchLatestRelease() } returns null

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

            feature.checkForUpdatesManually()
            advanceUntilIdle()

            assertEquals(SettingsManualUpdateState.UpToDate, feature.manualUpdateState.value)
        }

    @Test
    fun `manual update check exposes error state when checker throws`() =
        runTest {
            val fixture =
                systemFeatureFixture(
                    currentVersion = "0.9.1",
                    checkUpdatesOnStartupEnabled = true,
                )
            coEvery { fixture.appUpdateRepository.fetchLatestRelease() } throws IllegalStateException("network down")

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = this,
                    appConfigCoordinator = fixture.appConfigCoordinator,
                    appUpdateChecker = fixture.appUpdateChecker,
                    getCurrentAppVersionUseCase = fixture.getCurrentAppVersionUseCase,
                )

            feature.checkForUpdatesManually()
            advanceUntilIdle()

            assertEquals(
                SettingsManualUpdateState.Error("network down"),
                feature.manualUpdateState.value,
            )
        }

    private data class SystemFeatureFixture(
        val appConfigCoordinator: SettingsAppConfigCoordinator,
        val appUpdateChecker: AppUpdateChecker,
        val getCurrentAppVersionUseCase: GetCurrentAppVersionUseCase,
        val appUpdateRepository: AppUpdateRepository,
    )

    private fun TestScope.systemFeatureFixture(
        currentVersion: String,
        checkUpdatesOnStartupEnabled: Boolean = false,
    ): SystemFeatureFixture {
        val appConfigRepository = mockk<AppConfigRepository>(relaxed = true)
        val switchRootStorageUseCase = mockk<SwitchRootStorageUseCase>(relaxed = true)
        val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()
        val appUpdateRepository = mockk<AppUpdateRepository>()

        every { appConfigRepository.observeRootDisplayName() } returns flowOf(null)
        every { appConfigRepository.observeImageDisplayName() } returns flowOf(null)
        every { appConfigRepository.observeVoiceDisplayName() } returns flowOf(null)
        every { appConfigRepository.getDateFormat() } returns flowOf(PreferenceDefaults.DATE_FORMAT)
        every { appConfigRepository.getTimeFormat() } returns flowOf(PreferenceDefaults.TIME_FORMAT)
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { appConfigRepository.getStorageFilenameFormat() } returns flowOf(PreferenceDefaults.STORAGE_FILENAME_FORMAT)
        every { appConfigRepository.getStorageTimestampFormat() } returns flowOf(PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(PreferenceDefaults.SHOW_INPUT_HINTS)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)
        every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(PreferenceDefaults.FREE_TEXT_COPY_ENABLED)
        every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns
            flowOf(PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED)
        every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)
        every { appConfigRepository.isAppLockEnabled() } returns flowOf(PreferenceDefaults.APP_LOCK_ENABLED)
        every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(checkUpdatesOnStartupEnabled)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_TIME)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_BRAND)
        coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns currentVersion

        return SystemFeatureFixture(
            appConfigCoordinator =
                SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                ),
            appUpdateChecker =
                AppUpdateChecker(
                    checkAppUpdateUseCase =
                        CheckAppUpdateUseCase(
                            appUpdateRepository = appUpdateRepository,
                            appRuntimeInfoRepository = appRuntimeInfoRepository,
                        ),
                    checkStartupAppUpdateUseCase =
                        CheckStartupAppUpdateUseCase(
                            preferencesRepository = appConfigRepository,
                            appUpdateRepository = appUpdateRepository,
                            appRuntimeInfoRepository = appRuntimeInfoRepository,
                        ),
                ),
            getCurrentAppVersionUseCase =
                GetCurrentAppVersionUseCase(
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ),
            appUpdateRepository = appUpdateRepository,
        )
    }
}
