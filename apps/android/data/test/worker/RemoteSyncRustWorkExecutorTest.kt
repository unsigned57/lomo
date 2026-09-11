package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncRustWorkExecutor (P5-13 hollow-cycle close)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: production work unit over RemoteSyncRepository; fail-closed blank workspace /
 *   blank backend / missing-or-expired lease / boundary failures into RemoteSyncRetryHint without
 *   fixed three-retry policy; work unit probes opaque lease id + Rust-owned runCycle composed
 *   owner cycle (not empty-port inspectCyclePlan; not Kotlin planner).
 *
 * Scenarios:
 * - Given blank workspace root, when run, then Never and repository is not called.
 * - Given blank backend kind, when run, then Never without repository calls.
 * - Given non-blank workspace + hermetic backend without secret lease, when runCycle succeeds with
 *   after_user_action, then AfterUserAction and runCycle was invoked with workspace + backend.
 * - Given secret lease id present and probe succeeds, when runCycle succeeds, then AfterUserAction,
 *   probe saw opaque lease id (never plaintext), and runCycle ran with that lease.
 * - Given secret lease id blank string, when run, then Never without repository calls.
 * - Given probe throws secret_lease_missing / secret_lease_expired boundary, when run, then
 *   disposition Never (fail-closed) without runCycle.
 * - Given runCycle throws Transient boundary, when run, then Transient hint.
 * - Given runCycle returns never disposition name, when run, then Never.
 * - Given runCycle throws AfterUserAction boundary (e.g. conflict), when run, then
 *   AfterUserAction hint.
 * - Given unknown boundary disposition name, when run, then Never fail-closed.
 *
 * Observable outcomes: RemoteSyncRetryHint disposition; repository call counts / last request.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.RemoteSyncRustWorkExecutorTest'
 * - RED: executor still called inspectCyclePlan empty-port only.
 * - GREEN: runCycle surface + fail-closed + disposition map host-tested.
 *
 * Excludes:
 * - Real JNI / durable .lomo/sync (native sync_ffi_contract).
 * - Provider-specific sync bodies.
 * - Kotlin business planner.
 */

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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private class FakeRemoteSyncRepository : RemoteSyncRepository {
    var runCycleCount: Int = 0
    var inspectCount: Int = 0
    var listCount: Int = 0
    var probeCount: Int = 0
    var lastCycleRequest: RemoteSyncCycleRequest? = null
    var lastProbeLeaseId: String? = null

    var cycleSummary: RemoteSyncCyclePlanSummary =
        RemoteSyncCyclePlanSummary(
            sessionId = "session-dark",
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
    var probeLength: Int = 8
    var probeError: RemoteSyncBoundaryFailure? = null
    var runCycleError: RemoteSyncBoundaryFailure? = null

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage {
        listCount += 1
        error("listConflicts is not the production work unit surface; use runCycle")
    }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("resolve not used by work executor unit")

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = error("issue not used by work executor unit")

    override fun probeSecretLease(leaseId: String): Int {
        probeCount += 1
        lastProbeLeaseId = leaseId
        probeError?.let { throw it }
        return probeLength
    }

    override fun revokeSecretLease(leaseId: String) {
        error("revoke is owned by worker lease lifecycle, not work executor")
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
        error("inspectCyclePlan is readiness-only; production work unit must call runCycle")
    }

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary {
        runCycleCount += 1
        lastCycleRequest = request
        runCycleError?.let { throw it }
        return cycleSummary
    }
}

private fun hermeticRequest(
    workspaceRoot: String = "/ws",
    secretLeaseId: String? = null,
    secretFieldKey: String? = null,
): RustSyncWorkRequest =
    RustSyncWorkRequest(
        workspaceRoot = workspaceRoot,
        backendKind = "hermetic_fake",
        remoteDatasetId = "ds-test",
        secretFieldKey = secretFieldKey,
        secretLeaseId = secretLeaseId,
        applyRemote = false,
    )

class RemoteSyncRustWorkExecutorTest : FunSpec({
    test("blank workspace fail-closed Never without repository calls") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint = executor.run(hermeticRequest(workspaceRoot = "   "))

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
            hint.retryAfterMillis.shouldBeNull()
            repo.runCycleCount shouldBe 0
            repo.probeCount shouldBe 0
            repo.inspectCount shouldBe 0
            repo.listCount shouldBe 0
        }
    }

    test("blank backend kind fail-closed Never without repository calls") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint =
                executor.run(
                    RustSyncWorkRequest(
                        workspaceRoot = "/ws",
                        backendKind = "  ",
                        remoteDatasetId = "ds",
                    ),
                )

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
            repo.runCycleCount shouldBe 0
            repo.probeCount shouldBe 0
        }
    }

    test("no secret lease runs composed cycle and maps disposition to AfterUserAction") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint = executor.run(hermeticRequest())

            hint.disposition shouldBe RemoteSyncRetryDisposition.AfterUserAction
            hint.retryAfterMillis.shouldBeNull()
            repo.probeCount shouldBe 0
            repo.runCycleCount shouldBe 1
            repo.inspectCount shouldBe 0
            repo.listCount shouldBe 0
            repo.lastCycleRequest?.workspaceRoot shouldBe "/ws"
            repo.lastCycleRequest?.backendKind shouldBe "hermetic_fake"
            repo.lastCycleRequest?.remoteDatasetId shouldBe "ds-test"
            repo.lastCycleRequest?.applyRemote shouldBe false
        }
    }

    test("present lease probes opaque id then runs composed cycle") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint =
                executor.run(
                    hermeticRequest(
                        secretFieldKey = "WEBDAV_PASSWORD",
                        secretLeaseId = "lease-opaque-42",
                    ),
                )

            hint.disposition shouldBe RemoteSyncRetryDisposition.AfterUserAction
            repo.probeCount shouldBe 1
            repo.lastProbeLeaseId shouldBe "lease-opaque-42"
            repo.runCycleCount shouldBe 1
            repo.inspectCount shouldBe 0
            repo.listCount shouldBe 0
            repo.lastCycleRequest?.secretLeaseId shouldBe "lease-opaque-42"
        }
    }

    test("blank secret lease id fail-closed Never without probe or runCycle") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint =
                executor.run(
                    hermeticRequest(
                        secretFieldKey = "S3_SECRET_ACCESS_KEY",
                        secretLeaseId = "  ",
                    ),
                )

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
            repo.probeCount shouldBe 0
            repo.runCycleCount shouldBe 0
        }
    }

    test("missing lease boundary maps to Never without runCycle") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            repo.probeError =
                RemoteSyncBoundaryFailure(
                    category = "validation",
                    code = "secret_lease_missing",
                    retryDisposition = "never",
                    diagnostic = "secret lease is missing; process death or never issued",
                )
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint =
                executor.run(
                    hermeticRequest(secretLeaseId = "lease-never-issued"),
                )

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
            repo.probeCount shouldBe 1
            repo.runCycleCount shouldBe 0
        }
    }

    test("expired lease boundary maps to Never without runCycle") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            repo.probeError =
                RemoteSyncBoundaryFailure(
                    category = "validation",
                    code = "secret_lease_expired",
                    retryDisposition = "never",
                    diagnostic = "secret lease TTL elapsed; re-issue credentials for a new lease",
                )
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint =
                executor.run(
                    hermeticRequest(secretLeaseId = "lease-expired-1"),
                )

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
            repo.probeCount shouldBe 1
            repo.runCycleCount shouldBe 0
        }
    }

    test("runCycle Transient boundary maps to Transient") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            repo.runCycleError =
                RemoteSyncBoundaryFailure(
                    category = "network",
                    code = "sync_remote_unreachable",
                    retryDisposition = "transient",
                    diagnostic = "connection reset",
                )
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint = executor.run(hermeticRequest())

            hint.disposition shouldBe RemoteSyncRetryDisposition.Transient
            repo.runCycleCount shouldBe 1
            repo.inspectCount shouldBe 0
        }
    }

    test("runCycle AfterUserAction boundary maps to AfterUserAction") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            repo.runCycleError =
                RemoteSyncBoundaryFailure(
                    category = "conflict",
                    code = "conflict_session_open",
                    retryDisposition = "after_user_action",
                    diagnostic = "open conflicts require user resolution",
                )
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint = executor.run(hermeticRequest())

            hint.disposition shouldBe RemoteSyncRetryDisposition.AfterUserAction
            repo.runCycleCount shouldBe 1
        }
    }

    test("runCycle summary never disposition maps to Never") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            repo.cycleSummary = repo.cycleSummary.copy(retryDisposition = "never")
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint = executor.run(hermeticRequest())

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
            repo.runCycleCount shouldBe 1
        }
    }

    test("unknown boundary disposition fail-closed Never") {
        runTest {
            val repo = FakeRemoteSyncRepository()
            repo.runCycleError =
                RemoteSyncBoundaryFailure(
                    category = "internal",
                    code = "weird",
                    retryDisposition = "not_a_real_disposition",
                    diagnostic = "x",
                )
            val executor = RemoteSyncRustWorkExecutor(repo)

            val hint = executor.run(hermeticRequest())

            hint.disposition shouldBe RemoteSyncRetryDisposition.Never
        }
    }
})
