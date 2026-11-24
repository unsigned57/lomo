package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: ObserveDirectWorkspaceRootUseCase
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: project mounted writable Direct workspace root path for remote-sync observers.
 *
 * Scenarios:
 * - Given Opening, AwaitingWorkspaceSelection, ReadOnlyRecovery, Retained, Revalidating, or SAF
 *   content roots, when observe() collects, then null is emitted.
 * - Given Ready with Verified freshness and a Direct path, when observe() collects, then the
 *   canonical File.absolutePath is emitted.
 * - Given lifecycle transitions Opening -> Ready(Verified Direct) -> ReadOnlyRecovery -> Ready,
 *   when observe() collects, then transitions emit distinct root/null values without duplicates.
 *
 * Observable outcomes: distinct Flow<String?> emissions from FakeEngineReadinessRepository.mount.
 * TDD proof:
 * - Target: ./kotlin test --include-module=domain
 *   --include-classes='com.lomo.domain.usecase.ObserveDirectWorkspaceRootUseCaseTest'
 * - RED: observing raw DirectorySettingsRepository emitted a non-null Direct path before mount readiness.
 * - GREEN: emissions gate strictly on mount.admitsProjectionWrites and non-content locations.
 *
 * Excludes: DataStore persistence and Rust sync engine calls.
 */

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDirectWorkspaceRootUseCaseTest : DomainFunSpec() {
    init {
        test("emits null while opening, in recovery, or before projection writes are admitted") {
            runTest {
                val readiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                val useCase = ObserveDirectWorkspaceRootUseCase(readiness)

                useCase.observe().first() shouldBe null

                readiness.activateWorkspace(StorageLocation("/tmp/direct-notes"), mediaChange = null)
                readiness.publishProjectionFreshness(ProjectionFreshness.Retained(1uL))
                useCase.observe().first() shouldBe null

                readiness.publishProjectionFreshness(ProjectionFreshness.Revalidating(1uL))
                useCase.observe().first() shouldBe null

                readiness.publish(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.CONFLICT,
                        code = "memo_identity_unresolved",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "conflict",
                    ),
                )
                useCase.observe().first() shouldBe null
            }
        }

        test("emits null for SAF content mounts even when Ready and Verified") {
            runTest {
                val readiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                val useCase = ObserveDirectWorkspaceRootUseCase(readiness)

                readiness.activateWorkspace(
                    StorageLocation("content://com.android.externalstorage.documents/tree/primary%3ALomo"),
                    mediaChange = null,
                )

                useCase.observe().first() shouldBe null
            }
        }

        test("tracks distinct transitions from Opening to Verified Direct mount and back to recovery") {
            runTest {
                val readiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                val useCase = ObserveDirectWorkspaceRootUseCase(readiness)
                val emissions = mutableListOf<String?>()
                val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    useCase.observe().collect(emissions::add)
                }

                readiness.activateWorkspace(StorageLocation("/tmp/lomo-ws/./"), mediaChange = null)
                readiness.publishProjectionFreshness(ProjectionFreshness.Retained(1uL))
                readiness.publish(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.INTERNAL,
                        code = "io_error",
                        retryDisposition = EngineRetryDisposition.TRANSIENT,
                        diagnostic = "recovering",
                    ),
                )
                readiness.activateWorkspace(StorageLocation("/tmp/lomo-ws"), mediaChange = null)
                readiness.clearWorkspace()

                emissions shouldBe listOf(
                    null,
                    File("/tmp/lomo-ws/./").absolutePath,
                    null,
                    File("/tmp/lomo-ws").absolutePath,
                    null,
                )
                job.cancel()
            }
        }
    }
}
