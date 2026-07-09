package com.lomo.data.repository

import com.lomo.data.sync.SyncWorkPayload
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.repository.S3SyncOperationRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.reflect.full.memberFunctions

/*
 * Behavior Contract:
 * - Unit under test: S3 optimization containment contracts.
 * - Owning layer: data/domain boundary.
 * - Priority tier: P1.
 * - Capability: keep S3 remote-index and reconcile optimization state behind data-layer S3 adapters.
 *
 * Scenarios:
 * - Given the domain S3 operation repository contract, when its public operations are inspected, then sync does not accept provider optimization policy and remote-index state is not exposed.
 * - Given the backend-neutral work payload contract, when its variants are inspected, then it exposes generic remote work payloads rather than S3 scan/reconcile variants.
 *
 * Observable outcomes:
 * - Public method names/parameter counts and sealed work payload variant names at the compiled API boundary.
 *
 * TDD proof:
 * - RED: before the fix, this contract failed because S3SyncOperationRepository exposed sync(S3SyncScanPolicy) and getRemoteIndexState(), and SyncWorkPayload exposed S3Scan.
 *
 * Excludes:
 * - S3-local work intent selection, remote-index persistence, WorkManager request construction, and S3 transport execution.
 */
class S3OptimizationContainmentContractTest : DataFunSpec() {
    init {
        test("given domain s3 operation contract when inspected then optimization state is not exposed") {
            val methods = S3SyncOperationRepository::class.memberFunctions.toList()

            methods.map { method -> method.name } shouldNotContain "getRemoteIndexState"
            methods.single { method -> method.name == "sync" }.parameters.size shouldBe 1
        }

        test("given neutral work payload contract when inspected then no s3 scan variant is exposed") {
            SyncWorkPayload::class.java.permittedSubclasses
                .orEmpty()
                .map { subclass -> subclass.simpleName }
                .shouldContainExactlyInAnyOrder("StandardRemoteSync", "ProviderParameters")
        }
    }
}
