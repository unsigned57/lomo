package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateChecker
import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsSystemFeatureViewModel
 * - Behavior focus: current-version loading, inline manual update state transitions, error classification, and in-flight re-entry protection.
 * - Observable outcomes: exposed currentVersion and manualUpdateState values.
 * - Red phase: Fails before the fix because the settings system feature only toggles startup checks and exposes no version or manual update workflow.
 * - Excludes: Compose rendering, snackbar presentation, and repository transport details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSystemFeatureViewModelTest {
    @Test
    fun `initialization loads current version name`() =
        runTest {
            val appConfigCoordinator = mockk<SettingsAppConfigCoordinator>(relaxed = true)
            val appUpdateChecker = mockk<AppUpdateChecker>(relaxed = true)
            val getCurrentAppVersionUseCase = mockk<GetCurrentAppVersionUseCase>()

            every { appConfigCoordinator.checkUpdatesOnStartup } returns MutableStateFlow(false)
            coEvery { getCurrentAppVersionUseCase.invoke() } returns "0.9.1"

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = backgroundScope,
                    appConfigCoordinator = appConfigCoordinator,
                    appUpdateChecker = appUpdateChecker,
                    getCurrentAppVersionUseCase = getCurrentAppVersionUseCase,
                )

            advanceUntilIdle()

            assertEquals("0.9.1", feature.currentVersion.value)
            assertEquals(SettingsManualUpdateState.Idle, feature.manualUpdateState.value)
        }

    @Test
    fun `manual update check exposes inline update state and suppresses duplicate requests`() =
        runTest(StandardTestDispatcher()) {
            val appConfigCoordinator = mockk<SettingsAppConfigCoordinator>(relaxed = true)
            val getCurrentAppVersionUseCase = mockk<GetCurrentAppVersionUseCase>()
            val appUpdateChecker = mockk<AppUpdateChecker>()
            val gate = CompletableDeferred<Unit>()

            every { appConfigCoordinator.checkUpdatesOnStartup } returns MutableStateFlow(false)
            coEvery { getCurrentAppVersionUseCase.invoke() } returns "0.9.1"
            coEvery { appUpdateChecker.checkForManualUpdate() } coAnswers {
                gate.await()
                AppUpdateInfo(
                    url = "https://example.com/releases/1.0.0",
                    version = "1.0.0",
                    releaseNotes = "notes",
                )
            }

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = backgroundScope,
                    appConfigCoordinator = appConfigCoordinator,
                    appUpdateChecker = appUpdateChecker,
                    getCurrentAppVersionUseCase = getCurrentAppVersionUseCase,
                )

            feature.checkForUpdatesManually()
            runCurrent()

            assertEquals(SettingsManualUpdateState.Checking, feature.manualUpdateState.value)

            feature.checkForUpdatesManually()
            runCurrent()

            coVerify(exactly = 1) { appUpdateChecker.checkForManualUpdate() }

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
            val appConfigCoordinator = mockk<SettingsAppConfigCoordinator>(relaxed = true)
            val getCurrentAppVersionUseCase = mockk<GetCurrentAppVersionUseCase>()
            val appUpdateChecker = mockk<AppUpdateChecker>()

            every { appConfigCoordinator.checkUpdatesOnStartup } returns MutableStateFlow(true)
            coEvery { getCurrentAppVersionUseCase.invoke() } returns "0.9.1"
            coEvery { appUpdateChecker.checkForManualUpdate() } returns null

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = backgroundScope,
                    appConfigCoordinator = appConfigCoordinator,
                    appUpdateChecker = appUpdateChecker,
                    getCurrentAppVersionUseCase = getCurrentAppVersionUseCase,
                )

            feature.checkForUpdatesManually()
            advanceUntilIdle()

            assertEquals(SettingsManualUpdateState.UpToDate, feature.manualUpdateState.value)
        }

    @Test
    fun `manual update check exposes error state when checker throws`() =
        runTest {
            val appConfigCoordinator = mockk<SettingsAppConfigCoordinator>(relaxed = true)
            val getCurrentAppVersionUseCase = mockk<GetCurrentAppVersionUseCase>()
            val appUpdateChecker = mockk<AppUpdateChecker>()

            every { appConfigCoordinator.checkUpdatesOnStartup } returns MutableStateFlow(true)
            coEvery { getCurrentAppVersionUseCase.invoke() } returns "0.9.1"
            coEvery { appUpdateChecker.checkForManualUpdate() } throws IllegalStateException("network down")

            val feature =
                SettingsSystemFeatureViewModel(
                    scope = backgroundScope,
                    appConfigCoordinator = appConfigCoordinator,
                    appUpdateChecker = appUpdateChecker,
                    getCurrentAppVersionUseCase = getCurrentAppVersionUseCase,
                )

            feature.checkForUpdatesManually()
            advanceUntilIdle()

            assertEquals(
                SettingsManualUpdateState.Error("network down"),
                feature.manualUpdateState.value,
            )
        }
}
