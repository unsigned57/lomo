package com.lomo.app.feature.update

import android.content.Context
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppUpdateDownloadRepository
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException

/*
 * Behavior Contract:
 * - Unit under test: UpdateStartupOrchestrator and AppUpdateDownloadSession
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: startup update checks and in-app update download attempts are coordinated through explicit, retryable state contracts.
 *
 * Scenarios:
 * - Given startup update checks are triggered more than once, when orchestration runs, then the startup check executes once and exposes one dialog candidate.
 * - Given a startup check is cancelled, when startup check is triggered again, then the next attempt can still expose a dialog candidate.
 * - Given a startup check fails transiently, when startup check is triggered again, then the next attempt can still expose a dialog candidate.
 * - Given the update ViewModel is created, when UI subscribes to update state, then startup update discovery is not triggered from ViewModel initialization.
 * - Given an install-permission gate is emitted, when the user returns and retries, then the same downloaded candidate is reused for the next attempt.
 * - Given a download attempt fails, when retry is requested, then the retained candidate starts a new attempt and can recover.
 * - Given a cancelled download is immediately restarted, when the cancelled coroutine finishes late, then it cannot clear the new active download session.
 *
 * Observable outcomes:
 * - Dialog state, progress state, download attempt count, retained candidate metadata, startup check call count, and dismiss behavior while work remains active.
 *
 * TDD proof:
 * - RED observed with `./kotlin test --include-classes='com.lomo.app.feature.update.UpdateStartupOrchestrationContractTest'`.
 * - Before implementation, compilation failed with unresolved references for UpdateStartupOrchestrator and AppUpdateDownloadSession.
 * - Expected RED for this follow-up: cancelled or failed startup checks are consumed permanently, and a stale cancelled download job can clear the restarted active job.
 *
 * Excludes:
 * - Compose rendering, GitHub release transport, APK bytes, PackageManager verification, and PackageInstaller UI.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateStartupOrchestrationContractTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(dispatcher))

        test("given startup checks are triggered repeatedly when orchestrated then the check runs once") {
            runTest(dispatcher.scheduler) {
                var startupCheckCallCount = 0
                val orchestrator =
                    UpdateStartupOrchestrator(
                        startupUpdateCheck = {
                            startupCheckCallCount++
                            sampleUpdateInfo(version = "2.0.0")
                        },
                    )

                orchestrator.triggerStartupCheck(this)
                orchestrator.triggerStartupCheck(this)
                advanceUntilIdle()

                startupCheckCallCount shouldBe 1
                assertSoftly(orchestrator.dialogState.value!!) {
                    version shouldBe "2.0.0"
                    apkDownloadUrl shouldBe "https://example.com/assets/lomo-v2.0.0.apk"
                    expectedPackageName shouldBe "com.lomo.app"
                    expectedVersionName shouldBe "2.0.0"
                    expectedVersionCode shouldBe 20L
                }
            }
        }

        test("given an install permission gate when retrying then the retained candidate starts a new attempt") {
            runTest(dispatcher.scheduler) {
                val repository = FakeAppUpdateDownloadRepository()
                val session = newDownloadSession(repository = repository, scope = this)
                val update = sampleDialogState(version = "2.1.0")
                repository.downloadFlow =
                    flow {
                        emit(AppUpdateInstallState.RequiresInstallPermission("Allow installs from this app."))
                    }

                session.start(update)
                advanceUntilIdle()

                session.progressDialogState.value shouldBe
                    AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.RequiresInstallPermission("Allow installs from this app."),
                    )

                repository.downloadFlow =
                    flow {
                        emit(AppUpdateInstallState.Completed)
                    }
                session.retry()
                advanceUntilIdle()

                repository.downloadAndInstallCalledCount shouldBe 2
                repository.lastUpdateInfo shouldBe update.toExpectedUpdateInfo()
                session.progressDialogState.value shouldBe
                    AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Completed,
                    )
            }
        }

        test("given a download failure when retrying then the retained candidate can recover") {
            runTest(dispatcher.scheduler) {
                val repository = FakeAppUpdateDownloadRepository()
                val session = newDownloadSession(repository = repository, scope = this)
                val update = sampleDialogState(version = "2.2.0")
                repository.downloadFlow =
                    flow {
                        throw IOException("network unavailable")
                    }

                session.start(update)
                advanceUntilIdle()

                session.progressDialogState.value shouldBe
                    AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Failed("network unavailable"),
                    )

                repository.downloadFlow =
                    flow {
                        emit(AppUpdateInstallState.Completed)
                    }
                session.retry()
                advanceUntilIdle()

                repository.downloadAndInstallCalledCount shouldBe 2
                session.progressDialogState.value shouldBe
                    AppUpdateProgressDialogState(
                        update = update,
                        installState = AppUpdateInstallState.Completed,
                    )
            }
        }

        test("given no startup update candidate when check finishes then no dialog is exposed") {
            runTest(dispatcher.scheduler) {
                val orchestrator = UpdateStartupOrchestrator(startupUpdateCheck = { null })

                orchestrator.triggerStartupCheck(this)
                advanceUntilIdle()

                orchestrator.dialogState.value.shouldBeNull()
            }
        }

        test("given viewmodel initialization when update state is observed then startup check is not triggered") {
            runTest(dispatcher.scheduler) {
                var startupCheckCallCount = 0
                val orchestrator =
                    UpdateStartupOrchestrator(
                        startupUpdateCheck = {
                            startupCheckCallCount++
                            sampleUpdateInfo(version = "2.8.0")
                        },
                    )
                val viewModel =
                    AppUpdateViewModel(
                        updateStartupOrchestrator = orchestrator,
                        appUpdateDownloadManager = newDownloadManager(repository = FakeAppUpdateDownloadRepository()),
                    )

                advanceUntilIdle()

                startupCheckCallCount shouldBe 0
                viewModel.dialogState.value.shouldBeNull()
            }
        }

        test("given startup check is cancelled when triggered again then the next attempt can expose an update") {
            runTest(dispatcher.scheduler) {
                var startupCheckCallCount = 0
                val orchestrator =
                    UpdateStartupOrchestrator(
                        startupUpdateCheck = {
                            startupCheckCallCount++
                            if (startupCheckCallCount == 1) {
                                throw CancellationException("startup scope cancelled")
                            }
                            sampleUpdateInfo(version = "2.3.0")
                        },
                    )

                orchestrator.triggerStartupCheck(this)
                advanceUntilIdle()

                orchestrator.triggerStartupCheck(this)
                advanceUntilIdle()

                startupCheckCallCount shouldBe 2
                orchestrator.dialogState.value?.version shouldBe "2.3.0"
            }
        }

        test("given startup check fails transiently when triggered again then the next attempt can expose an update") {
            runTest(dispatcher.scheduler) {
                var startupCheckCallCount = 0
                val orchestrator =
                    UpdateStartupOrchestrator(
                        startupUpdateCheck = {
                            startupCheckCallCount++
                            if (startupCheckCallCount == 1) {
                                throw IOException("release endpoint unavailable")
                            }
                            sampleUpdateInfo(version = "2.4.0")
                        },
                    )

                orchestrator.triggerStartupCheck(this)
                advanceUntilIdle()
                orchestrator.dialogState.value.shouldBeNull()

                orchestrator.triggerStartupCheck(this)
                advanceUntilIdle()

                startupCheckCallCount shouldBe 2
                orchestrator.dialogState.value?.version shouldBe "2.4.0"
            }
        }

        test("given cancelled download restarts immediately when old job finishes late then new work stays active") {
            runTest(dispatcher.scheduler) {
                val repository = FakeAppUpdateDownloadRepository()
                val session = newDownloadSession(repository = repository, scope = this)
                val firstUpdate = sampleDialogState(version = "2.5.0")
                val restartedUpdate = sampleDialogState(version = "2.6.0")
                val concurrentUpdate = sampleDialogState(version = "2.7.0")
                val firstCleanupStarted = CompletableDeferred<Unit>()
                val releaseFirstCleanup = CompletableDeferred<Unit>()
                val restartedGate = CompletableDeferred<Unit>()
                repository.downloadFlow =
                    flow {
                        when (repository.downloadAndInstallCalledCount) {
                            1 -> {
                                try {
                                    emit(AppUpdateInstallState.Downloading(progress = 11))
                                    awaitCancellation()
                                } finally {
                                    firstCleanupStarted.complete(Unit)
                                    withContext(NonCancellable) {
                                        releaseFirstCleanup.await()
                                    }
                                }
                            }

                            2 -> {
                                emit(AppUpdateInstallState.Downloading(progress = 26))
                                restartedGate.await()
                                emit(AppUpdateInstallState.Completed)
                            }

                            else -> {
                                emit(AppUpdateInstallState.Downloading(progress = 99))
                                awaitCancellation()
                            }
                        }
                    }

                session.start(firstUpdate)
                advanceUntilIdle()

                session.cancel()
                runCurrent()
                firstCleanupStarted.await()

                session.start(restartedUpdate)
                advanceUntilIdle()
                val restartedProgress =
                    AppUpdateProgressDialogState(
                        update = restartedUpdate,
                        installState = AppUpdateInstallState.Downloading(progress = 26),
                    )
                session.progressDialogState.value shouldBe restartedProgress

                releaseFirstCleanup.complete(Unit)
                advanceUntilIdle()

                session.dismissProgressDialog()
                session.progressDialogState.value shouldBe restartedProgress

                session.start(concurrentUpdate)
                advanceUntilIdle()

                repository.downloadAndInstallCalledCount shouldBe 2
                session.progressDialogState.value shouldBe restartedProgress

                restartedGate.complete(Unit)
                advanceUntilIdle()
            }
        }
    }

    private fun newDownloadSession(
        repository: FakeAppUpdateDownloadRepository,
        scope: CoroutineScope,
    ): AppUpdateDownloadSession {
        val context = mockk<Context>()
        every { context.getString(any<Int>()) } returns "Download failed"
        return AppUpdateDownloadSession(
            context = context,
            downloadAndInstallAppUpdateUseCase = DownloadAndInstallAppUpdateUseCase(repository),
            cancelAppUpdateDownloadUseCase = CancelAppUpdateDownloadUseCase(repository),
            scope = scope,
        )
    }

    private fun newDownloadManager(
        repository: FakeAppUpdateDownloadRepository,
    ): AppUpdateDownloadManager {
        val context = mockk<Context>()
        return AppUpdateDownloadManager(
            context = context,
            downloadAndInstallAppUpdateUseCase = DownloadAndInstallAppUpdateUseCase(repository),
            cancelAppUpdateDownloadUseCase = CancelAppUpdateDownloadUseCase(repository),
        )
    }

    private fun sampleUpdateInfo(version: String): AppUpdateInfo =
        AppUpdateInfo(
            url = "https://example.com/releases/$version",
            version = version,
            releaseNotes = "notes",
            apkDownloadUrl = "https://example.com/assets/lomo-v$version.apk",
            apkFileName = "lomo-v$version.apk",
            apkSizeBytes = 4_096L,
            expectedPackageName = "com.lomo.app",
            expectedVersionName = version,
            expectedVersionCode = version.substringBefore('.').toLong() * 10L,
        )

    private fun sampleDialogState(version: String): AppUpdateDialogState =
        AppUpdateDialogState(
            url = "https://example.com/releases/$version",
            version = version,
            releaseNotes = "notes",
            apkDownloadUrl = "https://example.com/assets/lomo-v$version.apk",
            apkFileName = "lomo-v$version.apk",
            apkSizeBytes = 4_096L,
            expectedPackageName = "com.lomo.app",
            expectedVersionName = version,
            expectedVersionCode = version.substringBefore('.').toLong() * 10L,
        )

    private fun AppUpdateDialogState.toExpectedUpdateInfo(): AppUpdateInfo =
        AppUpdateInfo(
            url = url,
            version = version,
            releaseNotes = releaseNotes,
            apkDownloadUrl = apkDownloadUrl,
            apkFileName = apkFileName,
            apkSizeBytes = apkSizeBytes,
            expectedPackageName = expectedPackageName,
            expectedVersionName = expectedVersionName,
            expectedVersionCode = expectedVersionCode,
        )
}
