package com.lomo.app.feature.update

import android.content.Context
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: AppUpdateDownloadManager
 * - Behavior focus: user-visible in-app update progress transitions, duplicate-start suppression, and cancellation cleanup.
 * - Observable outcomes: exposed progressDialogState values and cancel-use-case dispatch.
 * - Red phase: Fails before the fix because the app has no shared in-app update download state machine; update actions only open GitHub in an external browser.
 * - Excludes: Compose dialog rendering, APK file transport internals, and Android package installer UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateDownloadManagerTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(dispatcher))
    }

    init {
        test("startInAppUpdate exposes progress states from the download use case") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val context = mockk<Context>(relaxed = true)
                val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
                val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()
                val update = sampleDialogState()

                every { cancelUseCase.invoke() } just runs
                every { downloadUseCase.invoke(any()) } returns
                    flow {
                        emit(AppUpdateInstallState.Preparing)
                        emit(AppUpdateInstallState.Downloading(progress = 42))
                        gate.await()
                        emit(AppUpdateInstallState.Completed)
                    }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                advanceUntilIdle()

                (manager.progressDialogState.value) shouldBe (AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Downloading(progress = 42),
                    ))

                gate.complete(Unit)
                advanceUntilIdle()

                (manager.progressDialogState.value) shouldBe (AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Completed,
                    ))
            }
        }
    }

    init {
        test("startInAppUpdate publishes preparing immediately before the download flow advances") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val context = mockk<Context>(relaxed = true)
                val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
                val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()
                val update = sampleDialogState()

                every { cancelUseCase.invoke() } just runs
                every { downloadUseCase.invoke(any()) } returns
                    flow {
                        gate.await()
                        emit(AppUpdateInstallState.Downloading(progress = 42))
                    }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                runCurrent()

                (manager.progressDialogState.value) shouldBe (AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Preparing,
                    ))

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }
    }

    init {
        test("startInAppUpdate waits until the next main-loop turn before showing progress dialog") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val context = mockk<Context>(relaxed = true)
                val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
                val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()
                val update = sampleDialogState()

                every { cancelUseCase.invoke() } just runs
                every { downloadUseCase.invoke(any()) } returns
                    flow {
                        emit(AppUpdateInstallState.Preparing)
                        gate.await()
                    }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)

                (manager.progressDialogState.value) shouldBe (null)

                runCurrent()

                (manager.progressDialogState.value) shouldBe (AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Preparing,
                    ))

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }
    }

    init {
        test("startInAppUpdate exposes install-permission recovery state from the download use case") {
            runTest(dispatcher.scheduler) {
                val context = mockk<Context>(relaxed = true)
                val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
                val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()
                val update = sampleDialogState()

                every { cancelUseCase.invoke() } just runs
                every { downloadUseCase.invoke(any()) } returns
                    flow {
                        emit(
                            AppUpdateInstallState.RequiresInstallPermission(
                                message = "Allow installs from this app to continue.",
                            ),
                        )
                    }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                advanceUntilIdle()

                (manager.progressDialogState.value) shouldBe (AppUpdateProgressDialogState(
                        update = update,
                        installState =
                            AppUpdateInstallState.RequiresInstallPermission(
                                message = "Allow installs from this app to continue.",
                            ),
                    ))
            }
        }
    }

    init {
        test("startInAppUpdate ignores duplicate requests while a download is already active") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val context = mockk<Context>(relaxed = true)
                val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
                val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()
                val update = sampleDialogState()
                var starts = 0

                every { cancelUseCase.invoke() } just runs
                every { downloadUseCase.invoke(any()) } answers {
                    starts += 1
                    flow {
                        emit(AppUpdateInstallState.Preparing)
                        gate.await()
                        emit(AppUpdateInstallState.Completed)
                    }
                }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(update)
                advanceUntilIdle()
                manager.startInAppUpdate(update)
                advanceUntilIdle()

                (starts) shouldBe (1)

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }
    }

    init {
        test("cancelInAppUpdate clears progress state and dispatches repository cancellation") {
            runTest(dispatcher.scheduler) {
                val gate = CompletableDeferred<Unit>()
                val context = mockk<Context>(relaxed = true)
                val downloadUseCase = mockk<DownloadAndInstallAppUpdateUseCase>()
                val cancelUseCase = mockk<CancelAppUpdateDownloadUseCase>()

                every { cancelUseCase.invoke() } just runs
                every { downloadUseCase.invoke(any()) } returns
                    flow {
                        emit(AppUpdateInstallState.Preparing)
                        gate.await()
                    }

                val manager = AppUpdateDownloadManager(context, downloadUseCase, cancelUseCase)

                manager.startInAppUpdate(sampleDialogState())
                advanceUntilIdle()

                manager.cancelInAppUpdate()
                advanceUntilIdle()

                (manager.progressDialogState.value) shouldBe (null)
                verify(exactly = 1) { cancelUseCase.invoke() }
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
