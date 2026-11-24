package com.lomo.app.feature.conflict

/*
 * Behavior Contract:
 * - Unit under test: SyncConflictStateViewModel
 * - Owning layer: app conflict orchestration
 * - Priority tier: P1
 * - Capability: gate Direct workspace conflict polling on mounted writable readiness and dispatch
 *   remote open-session queries onto DispatcherProvider.io.
 *
 * Scenarios:
 * - Given Opening, Retained, or ReadOnlyRecovery mount states, when collecting workspaceRoot,
 *   then workspaceRoot stays null; once Ready and Verified with Direct root, canonical path emits.
 * - Given loadRemoteOpenSession is invoked, when configSummary runs, then it executes on the
 *   injected IO dispatcher thread rather than the Main dispatcher thread.
 *
 * Observable outcomes: workspaceRoot StateFlow emissions and observed execution thread name.
 * TDD proof:
 * - Target: ./kotlin test --include-module=app
 *   --include-classes='com.lomo.app.feature.conflict.SyncConflictStateViewModelTest'
 * - RED: polling launched on Main and observed unmounted DataStore Direct paths, crashing on cold start.
 * - GREEN: workspaceRoot gates on mounted Verified Direct state and loadRemoteOpenSession hops to IO.
 *
 * Excludes: Compose UI hosts and real Rust FFI execution.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.collectWhileSubscribed
import com.lomo.app.testing.fakes.FakeEngineReadinessRepository
import com.lomo.app.testing.fakes.FakeMemoMutationRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncConflictSessionState
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.RemoteSyncCenterRepository
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.ObserveDirectWorkspaceRootUseCase
import com.lomo.domain.usecase.RemoteSyncConflictDialogUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictStateViewModelTest : AppFunSpec() {
    private val mainDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(mainDispatcher))

        test("workspaceRoot remains null until Direct workspace mount is Ready and Verified") {
            runTest(mainDispatcher) {
                val readiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                val ioDispatcher =
                    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-io-thread") }
                        .asCoroutineDispatcher()
                try {
                    val viewModel =
                        createViewModel(
                            readiness = readiness,
                            remoteRepo = RecordingThreadRemoteSyncRepository(),
                            ioDispatcher = ioDispatcher,
                        )
                    collectWhileSubscribed(viewModel.workspaceRoot)
                    advanceUntilIdle()
                    viewModel.workspaceRoot.value.shouldBeNull()

                    readiness.activateWorkspace(StorageLocation("/tmp/conflict-ws"), mediaChange = null)
                    readiness.publishProjectionFreshness(ProjectionFreshness.Retained(1uL))
                    advanceUntilIdle()
                    viewModel.workspaceRoot.value.shouldBeNull()

                    readiness.publish(
                        EngineReadiness.ReadOnlyRecovery(
                            category = EngineFailureCategory.CONFLICT,
                            code = "memo_identity_unresolved",
                            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                            diagnostic = "conflict",
                        ),
                    )
                    advanceUntilIdle()
                    viewModel.workspaceRoot.value.shouldBeNull()

                    readiness.activateWorkspace(StorageLocation("/tmp/conflict-ws"), mediaChange = null)
                    advanceUntilIdle()
                    viewModel.workspaceRoot.value shouldBe File("/tmp/conflict-ws").absolutePath
                } finally {
                    ioDispatcher.close()
                }
            }
        }

        test("loadRemoteOpenSession executes repository work on dispatcherProvider io") {
            runTest(mainDispatcher) {
                val readiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                val remoteRepo = RecordingThreadRemoteSyncRepository()
                val ioDispatcher =
                    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sync-conflict-io") }
                        .asCoroutineDispatcher()
                try {
                    val viewModel =
                        createViewModel(
                            readiness = readiness,
                            remoteRepo = remoteRepo,
                            ioDispatcher = ioDispatcher,
                        )

                    viewModel.loadRemoteOpenSession("/tmp/conflict-ws").shouldBeNull()

                    remoteRepo.configThreadName shouldStartWith "sync-conflict-io"
                } finally {
                    ioDispatcher.close()
                }
            }
        }
    }

    private fun createViewModel(
        readiness: FakeEngineReadinessRepository,
        remoteRepo: RemoteSyncCenterRepository,
        ioDispatcher: CoroutineDispatcher,
    ): SyncConflictStateViewModel = SyncConflictStateViewModel(
        syncProviderRegistry = SyncProviderRegistry(emptySet()),
        remoteSyncConflictDialogUseCase =
        RemoteSyncConflictDialogUseCase(
            remoteSyncCenterRepository = remoteRepo,
            memoRepository = FakeMemoMutationRepository(FakeMemoStore()),
        ),
        observeDirectWorkspaceRootUseCase = ObserveDirectWorkspaceRootUseCase(readiness),
        dispatcherProvider =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = mainDispatcher
            override val io: CoroutineDispatcher get() = ioDispatcher
            override val default: CoroutineDispatcher get() = mainDispatcher
            override val unconfined: CoroutineDispatcher get() = mainDispatcher
        },
    )

    private class RecordingThreadRemoteSyncRepository : RemoteSyncCenterRepository {
        var configThreadName: String = ""

        override suspend fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary {
            configThreadName = Thread.currentThread().name
            return RemoteSyncConfigSummary(
                backend = RemoteSyncBackendLabel.Git,
                attentionCount = 0,
                lastVerifiedAtEpochMillis = null,
                schedulePolicyLabel = null,
            )
        }

        override suspend fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
            RemoteSyncSessionProgress(
                phase = RemoteSyncSessionPhase.Idle,
                completedActions = 0,
                totalActions = null,
                canCancel = false,
            )

        override suspend fun requestCancel(workspaceRoot: String): RemoteSyncSessionProgress =
            sessionProgress(workspaceRoot)

        override fun listConflicts(workspaceRoot: String, cursor: Int, limit: Int): RemoteSyncConflictPage =
            RemoteSyncConflictPage(
                session = RemoteSyncConflictSessionState.Absent,
                sessionId = "",
                conflictRevision = 0L,
                items = emptyList(),
                nextCursor = null,
            )

        override fun resolveConflicts(
            workspaceRoot: String,
            expectedRevision: Long,
            resolutions: List<RemoteSyncConflictResolution>,
        ): RemoteSyncConflictResolveResult = error("unused")

        override fun markdownConflictFacts(
            workspaceRoot: String,
            path: RemoteSyncConflictPath,
            mergedDraft: String?,
        ): RemoteSyncMarkdownConflictFacts = error("unused")

        override fun binaryConflictFacts(
            workspaceRoot: String,
            path: RemoteSyncConflictPath,
        ): RemoteSyncBinaryConflictFacts = error("unused")
    }
}
