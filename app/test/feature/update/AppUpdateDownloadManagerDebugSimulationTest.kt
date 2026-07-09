package com.lomo.app.feature.update

import android.content.Context
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppUpdateDownloadRepository
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: AppUpdateDownloadManager
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: debug-only simulated in-app update states reuse the shared progress dialog state machine without invoking APK transport.
 *
 * Scenarios:
 * - Given a debug success update, when in-app update starts, then progress reaches Completed and the download repository is not called.
 * - Given a debug failure update, when in-app update starts, then progress reaches the localized Failed state and the download repository is not called.
 *
 * Observable outcomes:
 * - Exposed progressDialogState terminal state and fake repository download attempt count.
 *
 * TDD proof:
 * - RED observed with `./kotlin test --include-classes='com.lomo.app.feature.update.AppUpdateDownloadManagerDebugSimulationTest'`.
 * - Fails before debug simulation reuses the shared progress dialog state machine without calling the download repository.
 *
 * Excludes:
 * - Compose dialog rendering, settings item visibility wiring, and actual APK transport or installer behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateDownloadManagerDebugSimulationTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(dispatcher))

        test("startInAppUpdate completes a debug success simulation without invoking the real downloader") {
            runTest(dispatcher.scheduler) {
                val context = mockk<Context>()
                val repository = FakeAppUpdateDownloadRepository()
                val downloadUseCase = DownloadAndInstallAppUpdateUseCase(repository)
                val cancelUseCase = CancelAppUpdateDownloadUseCase(repository)

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)
                val update = sampleDialogState(debugSimulationScenario = DebugAppUpdateScenario.Success)

                manager.startInAppUpdate(update)
                advanceUntilIdle()

                manager.progressDialogState.value shouldBe
                    AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Completed,
                    )
                repository.downloadAndInstallCalledCount shouldBe 0
            }
        }

        test("startInAppUpdate exposes a failed state for debug failure simulation") {
            runTest(dispatcher.scheduler) {
                val context = mockk<Context>()
                val repository = FakeAppUpdateDownloadRepository()
                val downloadUseCase = DownloadAndInstallAppUpdateUseCase(repository)
                val cancelUseCase = CancelAppUpdateDownloadUseCase(repository)
                every { context.getString(any<Int>()) } returns "Simulated failure"

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)
                val update = sampleDialogState(debugSimulationScenario = DebugAppUpdateScenario.Failure)

                manager.startInAppUpdate(update)
                advanceUntilIdle()

                manager.progressDialogState.value shouldBe
                    AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Failed("Simulated failure"),
                    )
                repository.downloadAndInstallCalledCount shouldBe 0
            }
        }
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
