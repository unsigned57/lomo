package com.lomo.app.feature.update

import android.content.Context
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: AppUpdateDownloadManager
 * - Behavior focus: debug-only simulated in-app update states reuse the shared progress dialog state machine without invoking the real download use case.
 * - Observable outcomes: exposed progressDialogState terminal state and absence of real download-use-case invocation.
 * - Red phase: Fails before the fix because the manager has no debug simulation branch, so debug test-mode updates cannot drive the progress dialog without a real APK download.
 * - Excludes: Compose dialog rendering, settings item visibility wiring, and actual APK transport or installer behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateDownloadManagerDebugSimulationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startInAppUpdate completes a debug success simulation without invoking the real downloader`() =
        runTest(dispatcher.scheduler) {
            val context = mockk<Context>(relaxed = true)
            val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
            val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()

            every { cancelUseCase.invoke() } just runs
            every { downloadUseCase.invoke(any()) } returns emptyFlow()

            val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)
            val update = sampleDialogState(debugSimulationScenario = DebugAppUpdateScenario.Success)

            manager.startInAppUpdate(update)
            advanceUntilIdle()

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState = com.lomo.domain.model.AppUpdateInstallState.Completed,
                ),
                manager.progressDialogState.value,
            )
            verify(exactly = 0) { downloadUseCase.invoke(any()) }
        }

    @Test
    fun `startInAppUpdate exposes a failed state for debug failure simulation`() =
        runTest(dispatcher.scheduler) {
            val context = mockk<Context>(relaxed = true)
            val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
            val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()

            every { cancelUseCase.invoke() } just runs
            every { downloadUseCase.invoke(any()) } returns emptyFlow()
            every { context.getString(com.lomo.app.R.string.debug_update_simulation_failed) } returns "Simulated failure"

            val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)
            val update = sampleDialogState(debugSimulationScenario = DebugAppUpdateScenario.Failure)

            manager.startInAppUpdate(update)
            advanceUntilIdle()

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState = com.lomo.domain.model.AppUpdateInstallState.Failed("Simulated failure"),
                ),
                manager.progressDialogState.value,
            )
            verify(exactly = 0) { downloadUseCase.invoke(any()) }
        }

    private fun sampleDialogState(debugSimulationScenario: DebugAppUpdateScenario): AppUpdateDialogState =
        AppUpdateDialogState(
            url = "debug://app-update-preview",
            version = "1.0.1-preview",
            releaseNotes = "debug preview",
            apkDownloadUrl = "debug://app-update.apk",
            apkFileName = "lomo-debug-preview.apk",
            apkSizeBytes = 4096L,
            debugSimulationScenario = debugSimulationScenario,
        )
}
