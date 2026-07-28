package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: RustSyncWorker + real RemoteSyncRustWorkExecutor composition (dark Wave-8)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: prove lease issue → probe → composed runCycle → revoke + disposition→WM with
 *   the production-shaped executor class (not a FakeRustSyncWorkExecutor stand-in).
 *
 * Scenarios:
 * - Given secret field material, when doWork runs with real RemoteSyncRustWorkExecutor + fake repo,
 *   then lease issued, probe+runCycle on opaque id, Result.success from after_user_action, lease revoked.
 * - Given blank workspace, when doWork runs with real executor, then Failure and no runCycle.
 * - Given missing secret material, when doWork runs, then Failure and executor never reached.
 * - Given runCycle Transient boundary, when doWork runs, then Result.retry and lease still revoked.
 *
 * Observable outcomes: ListenableWorker.Result; lease issue/revoke; repo probe/runCycle counts.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.RustSyncWorkerCompositionTest'
 * - RED: composition still inspect-only.
 * - GREEN: production-shaped executor + worker body host-tested on runCycle.
 *
 * Excludes:
 * - Real JNI / durable .lomo/sync.
 * - Full provider plan/apply publish.
 */

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult
import com.lomo.data.engine.sync.RemoteSyncConflictResolution
import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RemoteSyncRetryDisposition
import com.lomo.data.engine.sync.RemoteSyncRetryHint
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

private class CompositionFakeRepository : RemoteSyncRepository {
    var probeCount: Int = 0
    var runCycleCount: Int = 0
    var inspectCount: Int = 0
    var lastProbeLeaseId: String? = null
    var lastRunCycleRequest: RemoteSyncCycleRequest? = null
    var probeError: RemoteSyncBoundaryFailure? = null
    var runCycleError: RemoteSyncBoundaryFailure? = null
    var cycleSummary: RemoteSyncCyclePlanSummary =
        RemoteSyncCyclePlanSummary(
            sessionId = "compose-session",
            sessionKind = "first_takeover",
            sessionRevision = 1L,
            baselineEstablished = false,
            ensurePresentCount = 1,
            ensureAbsentCount = 0,
            pullPresentCount = 0,
            openConflictCount = 0,
            openConflictPaths = 0,
            conflictRevision = null,
            retryDisposition = "after_user_action",
        )

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage = error("composition uses runCycle only")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("resolve not used")

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = error("issue owned by secret supplier")

    override fun probeSecretLease(leaseId: String): Int {
        probeCount += 1
        lastProbeLeaseId = leaseId
        probeError?.let { throw it }
        return 16
    }

    override fun revokeSecretLease(leaseId: String) {
        error("revoke owned by worker lifecycle")
    }

    override fun retryHintFromDispositionName(name: String): RemoteSyncRetryHint {
        val disposition =
            when (name.trim().lowercase()) {
                "never" -> RemoteSyncRetryDisposition.Never
                "after_user_action" -> RemoteSyncRetryDisposition.AfterUserAction
                "transient" -> RemoteSyncRetryDisposition.Transient
                else -> RemoteSyncRetryDisposition.Never
            }
        return RemoteSyncRetryHint(disposition = disposition)
    }

    override fun inspectCyclePlan(workspaceRoot: String): RemoteSyncCyclePlanSummary {
        inspectCount += 1
        error("composition production path must call runCycle, not inspectCyclePlan")
    }

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary {
        runCycleCount += 1
        lastRunCycleRequest = request
        runCycleError?.let { throw it }
        return cycleSummary
    }
}

private class CompositionFakeSecretSupplier(
    private val leasesByField: MutableMap<String, String> = mutableMapOf(),
) : RustSyncSecretSupplier {
    var issueCount: Int = 0
    var revokeCount: Int = 0
    val revokedIds: MutableList<String> = mutableListOf()

    fun putLease(
        fieldKey: String,
        leaseId: String,
    ) {
        leasesByField[fieldKey] = leaseId
    }

    override fun issueLease(
        fieldKey: String,
        ttlMillis: Long,
    ): RemoteSyncSecretLease? {
        issueCount += 1
        val id = leasesByField[fieldKey] ?: return null
        return RemoteSyncSecretLease(leaseId = id)
    }

    override fun revokeLease(leaseId: String) {
        revokeCount += 1
        revokedIds += leaseId
    }
}

class RustSyncWorkerCompositionTest : FunSpec({
    test("composition issues lease, real executor probes+runCycle, maps success, revokes") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-compose",
                    applyRemote = false,
                    workspaceRoot = "/ws",
                    secretFieldKey = "webdav_password",
                    leaseTtlMillis = 45_000,
                )
            val supplier = CompositionFakeSecretSupplier()
            supplier.putLease("webdav_password", "lease-compose-1")
            val repo = CompositionFakeRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
            supplier.issueCount shouldBe 1
            supplier.revokeCount shouldBe 1
            supplier.revokedIds shouldBe listOf("lease-compose-1")
            repo.probeCount shouldBe 1
            repo.lastProbeLeaseId shouldBe "lease-compose-1"
            repo.runCycleCount shouldBe 1
            repo.inspectCount shouldBe 0
            repo.lastRunCycleRequest?.workspaceRoot shouldBe "/ws"
            repo.lastRunCycleRequest?.backendKind shouldBe "hermetic_fake"
        }
    }

    test("composition blank workspace fails closed without inspect") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns workDataOfBlank()
            val supplier = CompositionFakeSecretSupplier()
            val repo = CompositionFakeRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            repo.runCycleCount shouldBe 0
            repo.inspectCount shouldBe 0
            supplier.issueCount shouldBe 0
        }
    }

    test("composition missing secret fails closed before executor") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-compose",
                    applyRemote = false,
                    workspaceRoot = "/ws",
                    secretFieldKey = "webdav_password",
                )
            val supplier = CompositionFakeSecretSupplier()
            val repo = CompositionFakeRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            supplier.issueCount shouldBe 1
            repo.runCycleCount shouldBe 0
            repo.inspectCount shouldBe 0
            repo.probeCount shouldBe 0
            supplier.revokeCount shouldBe 0
        }
    }

    test("composition runCycle Transient maps to retry and still revokes lease") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-compose",
                    applyRemote = false,
                    workspaceRoot = "/ws",
                    secretFieldKey = "s3_secret",
                )
            val supplier = CompositionFakeSecretSupplier()
            supplier.putLease("s3_secret", "lease-transient")
            val repo = CompositionFakeRepository()
            repo.runCycleError =
                RemoteSyncBoundaryFailure(
                    category = "network",
                    code = "sync_remote_unreachable",
                    retryDisposition = "transient",
                    diagnostic = "connection reset",
                )
            val executor = RemoteSyncRustWorkExecutor(repo)

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
            supplier.revokeCount shouldBe 1
            supplier.revokedIds shouldBe listOf("lease-transient")
            repo.probeCount shouldBe 1
            repo.runCycleCount shouldBe 1
        }
    }
})

private fun workDataOfBlank() =
    RustSyncWorker.inputData(backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-compose",
                    applyRemote = false,
                    workspaceRoot = "  ")
