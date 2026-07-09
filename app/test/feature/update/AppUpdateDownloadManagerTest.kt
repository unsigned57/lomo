package com.lomo.app.feature.update

/**
 * Behavior Contract:
 * - Unit under test: AppUpdateDownloadManager
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: in-app update downloads expose progress, terminal, permission, duplicate-start, and cancel state through a shared dialog state machine.
 *
 * Scenarios:
 * - Given download progress is emitted, when in-app update starts, then progressDialogState mirrors the latest install state.
 * - Given the download flow is initially suspended, when in-app update starts, then Preparing appears on the next main-loop turn.
 * - Given install permission is required, when the use case emits the gate, then the same gate is exposed to the dialog.
 * - Given a download is active, when a duplicate start is requested, then no second repository download starts.
 * - Given a download is active, when cancellation is requested, then progress is cleared and repository cancellation is dispatched.
 *
 * Observable outcomes:
 * - Progress dialog state values, fake repository download attempt count, and fake repository cancellation count.
 *
 * TDD proof:
 * - Not applicable - test-only contract migration; no production change.
 *
 * Excludes:
 * - Compose rendering, debug simulation, update release transport, APK verification, and platform installer UI.
 */
import android.content.Context
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppUpdateDownloadRepository
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateDownloadManagerTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()
    private val appUpdateDownloadRepository = FakeAppUpdateDownloadRepository()
    private val downloadUseCase = DownloadAndInstallAppUpdateUseCase(appUpdateDownloadRepository)
    private val cancelUseCase = CancelAppUpdateDownloadUseCase(appUpdateDownloadRepository)
    private val context = mockk<Context>()

    init {
        extension(MainDispatcherExtension(dispatcher))

        test("startInAppUpdate exposes progress states from the download use case") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val update = sampleDialogState()

                appUpdateDownloadRepository.downloadFlow = flow {
                    emit(AppUpdateInstallState.Preparing)
                    emit(AppUpdateInstallState.Downloading(progress = 42))
                    gate.await()
                    emit(AppUpdateInstallState.Completed)
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                advanceUntilIdle()

                manager.progressDialogState.value shouldBe AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Downloading(progress = 42),
                )

                gate.complete(Unit)
                advanceUntilIdle()

                manager.progressDialogState.value shouldBe AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Completed,
                )
            }
        }

        test("startInAppUpdate publishes preparing immediately before the download flow advances") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val update = sampleDialogState()

                appUpdateDownloadRepository.downloadFlow = flow {
                    gate.await()
                    emit(AppUpdateInstallState.Downloading(progress = 42))
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                runCurrent()

                manager.progressDialogState.value shouldBe AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Preparing,
                )

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }

        test("startInAppUpdate waits until the next main-loop turn before showing progress dialog") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val update = sampleDialogState()

                appUpdateDownloadRepository.downloadFlow = flow {
                    emit(AppUpdateInstallState.Preparing)
                    gate.await()
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)

                manager.progressDialogState.value shouldBe null

                runCurrent()

                manager.progressDialogState.value shouldBe AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Preparing,
                )

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }

        test("startInAppUpdate exposes install-permission recovery state from the download use case") {
            runTest(dispatcher.scheduler) {
                val update = sampleDialogState()

                appUpdateDownloadRepository.downloadFlow = flow {
                    emit(
                        AppUpdateInstallState.RequiresInstallPermission(
                            message = "Allow installs from this app to continue.",
                        ),
                    )
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                advanceUntilIdle()

                manager.progressDialogState.value shouldBe AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.RequiresInstallPermission(
                        message = "Allow installs from this app to continue.",
                    ),
                )
            }
        }

        test("startInAppUpdate ignores duplicate requests while a download is already active") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val update = sampleDialogState()

                appUpdateDownloadRepository.downloadFlow = flow {
                    emit(AppUpdateInstallState.Preparing)
                    gate.await()
                    emit(AppUpdateInstallState.Completed)
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                advanceUntilIdle()
                manager.startInAppUpdate(update)
                advanceUntilIdle()

                appUpdateDownloadRepository.downloadAndInstallCalledCount shouldBe 1

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }

        test("cancelInAppUpdate clears progress state and dispatches repository cancellation") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()

                appUpdateDownloadRepository.downloadFlow = flow {
                    emit(AppUpdateInstallState.Preparing)
                    gate.await()
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(sampleDialogState())
                advanceUntilIdle()

                manager.cancelInAppUpdate()
                advanceUntilIdle()

                manager.progressDialogState.value shouldBe null
                appUpdateDownloadRepository.cancelCurrentDownloadCalledCount shouldBe 1
                gate.complete(Unit)
            }
        }
    }

    private fun sampleDialogState(): AppUpdateDialogState =
        AppUpdateDialogState(
            url = "https://example.com/releases/1.2.0",
            version = "1.2.0",
            releaseNotes = "notes",
            apkDownloadUrl = "https://example.com/assets/lomo-v1.2.0.apk",
            apkFileName = "lomo-v1.2.0.apk",
            apkSizeBytes = 4096L,
        )
}
