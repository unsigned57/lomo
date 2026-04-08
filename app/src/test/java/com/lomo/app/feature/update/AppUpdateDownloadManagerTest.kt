package com.lomo.app.feature.update

import android.content.Context
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: AppUpdateDownloadManager
 * - Behavior focus: user-visible in-app update progress transitions, duplicate-start suppression, and cancellation cleanup.
 * - Observable outcomes: exposed progressDialogState values and cancel-use-case dispatch.
 * - Red phase: Fails before the fix because the app has no shared in-app update download state machine; update actions only open GitHub in an external browser.
 * - Excludes: Compose dialog rendering, APK file transport internals, and Android package installer UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateDownloadManagerTest {
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
    fun `startInAppUpdate exposes progress states from the download use case`() =
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

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Downloading(progress = 42),
                ),
                manager.progressDialogState.value,
            )

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Completed,
                ),
                manager.progressDialogState.value,
            )
        }

    @Test
    fun `startInAppUpdate publishes preparing immediately before the download flow advances`() =
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

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Preparing,
                ),
                manager.progressDialogState.value,
            )

            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `startInAppUpdate waits until the next main-loop turn before showing progress dialog`() =
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

            assertEquals(null, manager.progressDialogState.value)

            runCurrent()

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState = AppUpdateInstallState.Preparing,
                ),
                manager.progressDialogState.value,
            )

            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `startInAppUpdate exposes install-permission recovery state from the download use case`() =
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

            assertEquals(
                AppUpdateProgressDialogState(
                    update = update,
                    installState =
                        AppUpdateInstallState.RequiresInstallPermission(
                            message = "Allow installs from this app to continue.",
                        ),
                ),
                manager.progressDialogState.value,
            )
        }

    @Test
    fun `startInAppUpdate ignores duplicate requests while a download is already active`() =
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

            assertEquals(1, starts)

            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `cancelInAppUpdate clears progress state and dispatches repository cancellation`() =
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

            assertEquals(null, manager.progressDialogState.value)
            verify(exactly = 1) { cancelUseCase.invoke() }
            gate.complete(Unit)
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
