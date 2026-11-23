package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: ReconcileDraftMediaUseCase
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: restart-time reconcile of one recovered draft's staged-media leases.
 *
 * Scenarios:
 * - Given the engine is Ready, when invoke runs, then the reconciliation is returned.
 * - Given the engine is still Opening, when invoke runs, then the call suspends — the stage
 *   ledger lives inside the mounted workspace adapter and reconciling earlier throws its
 *   Ready precondition on the caller's thread — and completes once Ready publishes.
 * - Given reconciliation reports missing staged bytes, when invoke runs, then a typed
 *   RecoverableDraftFailure names the destinations.
 *
 * Observable outcomes: FakeMediaRepository reconcile call ledger, returned reconciliation,
 * thrown RecoverableDraftFailure.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.ReconcileDraftMediaUseCaseTest'
 * - RED: invoke raced the mounted-adapter precondition when draft recovery ran during Opening.
 * - GREEN: invoke waits on EngineReadiness.Ready before touching the repository.
 *
 * Excludes: adapter-side precondition crash itself (data/Rust boundary).
 */

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.DraftMediaReconciliation
import com.lomo.domain.model.DraftStagedMediaRef
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.RecoverableDraftFailure
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeMediaRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class ReconcileDraftMediaUseCaseTest : DomainFunSpec() {
    private val mediaRepository = FakeMediaRepository()
    private val readinessRepository = FakeEngineReadinessRepository()
    private val useCase = ReconcileDraftMediaUseCase(mediaRepository, readinessRepository)

    init {
        test("returns the reconciliation when the engine is ready") {
            runTest {
                readinessRepository.publish(EngineReadiness.Ready)
                val expected =
                    DraftMediaReconciliation(
                        draftId = DraftId("draft-1"),
                        records = listOf(DraftStagedMediaRef("a-1", "media/a.jpg", true)),
                    )
                mediaRepository.reconcileResult = expected

                useCase(DraftId("draft-1")) shouldBe expected
                mediaRepository.reconciledDraftIds shouldBe listOf(DraftId("draft-1"))
            }
        }

        test("suspends on the stage-ledger read while the engine is opening") {
            runTest {
                readinessRepository.publish(EngineReadiness.Opening)
                val deferred = async { useCase(DraftId("draft-1")) }
                testScheduler.runCurrent()

                mediaRepository.reconciledDraftIds.shouldBeEmpty()
                deferred.isCompleted shouldBe false

                readinessRepository.publish(EngineReadiness.Ready)
                testScheduler.advanceUntilIdle()

                deferred.await().draftId shouldBe DraftId("draft-1")
                mediaRepository.reconciledDraftIds shouldBe listOf(DraftId("draft-1"))
            }
        }

        test("admits stage-ledger reconcile during Revalidating and Retained mounts") {
            runTest {
                readinessRepository.publishProjectionFreshness(ProjectionFreshness.Revalidating(0uL))
                readinessRepository.publish(EngineReadiness.Ready)

                useCase(DraftId("draft-revalidating")).draftId shouldBe DraftId("draft-revalidating")

                readinessRepository.publishProjectionFreshness(ProjectionFreshness.Retained(0uL))
                readinessRepository.publish(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.STORAGE,
                        code = "metadata_enumeration_loading",
                        retryDisposition = EngineRetryDisposition.TRANSIENT,
                        diagnostic = "provider loading",
                    ),
                )

                useCase(DraftId("draft-retained")).draftId shouldBe DraftId("draft-retained")
                mediaRepository.reconciledDraftIds shouldBe
                    listOf(DraftId("draft-revalidating"), DraftId("draft-retained"))
            }
        }

        test("missing staged bytes fail with the named destinations") {
            runTest {
                readinessRepository.publish(EngineReadiness.Ready)
                mediaRepository.reconcileResult =
                    DraftMediaReconciliation(
                        draftId = DraftId("draft-1"),
                        records =
                        listOf(
                            DraftStagedMediaRef("a-1", "media/a.jpg", false),
                            DraftStagedMediaRef("a-2", "media/b.png", false),
                        ),
                    )

                val failure =
                    shouldThrow<RecoverableDraftFailure> { useCase(DraftId("draft-1")) }

                failure.missingDestinations shouldBe listOf("media/a.jpg", "media/b.png")
            }
        }
    }
}
