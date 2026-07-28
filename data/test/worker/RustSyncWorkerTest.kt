package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: RustSyncRetryPolicy + RustSyncWorker body (dark P5-09 residual)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: CoroutineWorker-shaped orchestration over secret lease + work executor + disposition
 *   → WorkManager results without fixed three-retry business logic; fail-closed missing lease;
 *   always revoke issued lease; cancel/stale stops without forcing failure.
 *
 * Scenarios:
 * - Given Never/AfterUserAction/Transient hints, when workResult maps, then failure/success/retry.
 * - Given Transient with retryAfterMillis, when retryAfterMillis is read, then positive delay is
 *   preserved; Never/AfterUserAction always null.
 * - Given blank workspace root, when doWork runs, then Result.failure (Never) and executor never runs.
 * - Given required secret field with no material, when doWork runs, then Result.failure (fail-closed
 *   missing lease) and executor never runs.
 * - Given secret field present, when doWork succeeds Transient, then Result.retry, lease issued then
 *   revoked, and executor saw lease id (never plaintext field as lease id).
 * - Given boundary failure with disposition never / transient, when doWork runs, then mapped Result
 *   and issued lease is still revoked.
 * - Given worker already stopped before run, when doWork runs, then Result.success without executor.
 * - Given unexpected Exception (not boundary), when doWork runs, then Transient retry (no maxAttempts=3).
 *
 * Observable outcomes: ListenableWorker.Result type; lease issue/revoke counts; executor request
 * fields; optional delay Long?.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.RustSyncWorkerTest'
 * - RED: hollow policy-only stub lacked CoroutineWorker body / lease orchestration.
 * - GREEN: disposition mapping + lease fail-closed + revoke + cancel path host-tested while unregistered.
 *
 * Excludes:
 * - WorkManager enqueue / Koin workerOf registration (P5-13).
 * - Provider sync execution bodies / real JNI.
 */

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncRetryDisposition
import com.lomo.data.engine.sync.RemoteSyncRetryHint
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

private class FakeRustSyncSecretSupplier(
    private val leasesByField: MutableMap<String, String> = mutableMapOf(),
) : RustSyncSecretSupplier {
    var issueCount: Int = 0
    var revokeCount: Int = 0
    val revokedIds: MutableList<String> = mutableListOf()
    var throwOnIssue: Exception? = null

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
        throwOnIssue?.let { throw it }
        issueCount += 1
        val id = leasesByField[fieldKey] ?: return null
        return RemoteSyncSecretLease(leaseId = id)
    }

    override fun revokeLease(leaseId: String) {
        revokeCount += 1
        revokedIds += leaseId
    }
}

private class FakeRustSyncWorkExecutor(
    private val hint: RemoteSyncRetryHint =
        RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.AfterUserAction),
) : RustSyncWorkExecutor {
    var runCount: Int = 0
    var lastRequest: RustSyncWorkRequest? = null
    var throwError: Exception? = null

    override suspend fun run(request: RustSyncWorkRequest): RemoteSyncRetryHint {
        runCount += 1
        lastRequest = request
        throwError?.let { throw it }
        return hint
    }
}

class RustSyncWorkerTest : FunSpec({
    test("Never maps to failure without fixed three-retry") {
        val result =
            RustSyncRetryPolicy.workResult(
                RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Never),
            )
        result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        RustSyncRetryPolicy
            .retryAfterMillis(
                RemoteSyncRetryHint(
                    disposition = RemoteSyncRetryDisposition.Never,
                    retryAfterMillis = 5_000,
                ),
            ).shouldBeNull()
    }

    test("AfterUserAction maps to success so automatic retry stops") {
        val result =
            RustSyncRetryPolicy.workResult(
                RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.AfterUserAction),
            )
        result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
        RustSyncRetryPolicy
            .retryAfterMillis(
                RemoteSyncRetryHint(
                    disposition = RemoteSyncRetryDisposition.AfterUserAction,
                    retryAfterMillis = 5_000,
                ),
            ).shouldBeNull()
    }

    test("Transient maps to retry and preserves positive retryAfter") {
        val hint =
            RemoteSyncRetryHint(
                disposition = RemoteSyncRetryDisposition.Transient,
                retryAfterMillis = 12_000,
            )
        RustSyncRetryPolicy.workResult(hint).shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        RustSyncRetryPolicy.retryAfterMillis(hint) shouldBe 12_000L
        RustSyncRetryPolicy.retryAfterMillis(
            RemoteSyncRetryHint(
                disposition = RemoteSyncRetryDisposition.Transient,
                retryAfterMillis = 0,
            ),
        ).shouldBeNull()
    }

    test("RustSyncWorker companion delegates to disposition policy") {
        RustSyncWorker
            .mapRetryHint(
                RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Transient),
            ).shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        RustSyncWorker.WORK_NAME shouldBe "com.lomo.data.worker.RustSyncWorker"
    }

    test("doWork fail-closed when workspace root is blank") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns workDataOf()
            val supplier = FakeRustSyncSecretSupplier()
            val executor = FakeRustSyncWorkExecutor()

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            executor.runCount shouldBe 0
            supplier.issueCount shouldBe 0
        }
    }

    test("doWork fail-closed when required secret lease is missing") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-worker",
                    workspaceRoot = "/ws",
                    secretFieldKey = "webdav_password",
                )
            val supplier = FakeRustSyncSecretSupplier() // no putLease → null lease
            val executor = FakeRustSyncWorkExecutor()

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            executor.runCount shouldBe 0
            supplier.issueCount shouldBe 1
            supplier.revokeCount shouldBe 0
        }
    }

    test("doWork issues lease, runs executor, maps Transient, and always revokes") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-worker",
                    workspaceRoot = "/ws",
                    secretFieldKey = "webdav_password",
                    leaseTtlMillis = 30_000,
                )
            val supplier = FakeRustSyncSecretSupplier()
            supplier.putLease("webdav_password", "lease-opaque-1")
            val executor =
                FakeRustSyncWorkExecutor(
                    hint =
                        RemoteSyncRetryHint(
                            disposition = RemoteSyncRetryDisposition.Transient,
                            retryAfterMillis = 9_000,
                        ),
                )

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
            executor.runCount shouldBe 1
            val request = executor.lastRequest.shouldNotBeNull()
            request.workspaceRoot shouldBe "/ws"
            request.secretFieldKey shouldBe "webdav_password"
            request.secretLeaseId shouldBe "lease-opaque-1"
            request.leaseTtlMillis shouldBe 30_000L
            supplier.issueCount shouldBe 1
            supplier.revokeCount shouldBe 1
            supplier.revokedIds shouldBe listOf("lease-opaque-1")
        }
    }

    test("doWork without secret field runs executor and maps AfterUserAction to success") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws")
            val supplier = FakeRustSyncSecretSupplier()
            val executor =
                FakeRustSyncWorkExecutor(
                    hint = RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.AfterUserAction),
                )

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
            executor.runCount shouldBe 1
            executor.lastRequest!!.secretLeaseId.shouldBeNull()
            supplier.issueCount shouldBe 0
            supplier.revokeCount shouldBe 0
        }
    }

    test("doWork maps boundary failure disposition and still revokes issued lease") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-worker",
                    workspaceRoot = "/ws",
                    secretFieldKey = "s3_secret",
                )
            val supplier = FakeRustSyncSecretSupplier()
            supplier.putLease("s3_secret", "lease-boundary")
            val executor =
                FakeRustSyncWorkExecutor().apply {
                    throwError =
                        RemoteSyncBoundaryFailure(
                            category = "network",
                            code = "sync_remote_unreachable",
                            retryDisposition = "transient",
                            diagnostic = "connection reset",
                        )
                }

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
            supplier.revokeCount shouldBe 1
            supplier.revokedIds shouldBe listOf("lease-boundary")
        }
    }

    test("doWork maps unknown boundary disposition to Never fail-closed") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws")
            val supplier = FakeRustSyncSecretSupplier()
            val executor =
                FakeRustSyncWorkExecutor().apply {
                    throwError =
                        RemoteSyncBoundaryFailure(
                            category = "validation",
                            code = "weird",
                            retryDisposition = "not_a_real_disposition",
                            diagnostic = "x",
                        )
                }

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        }
    }

    test("doWork returns success without running when stop probe is set") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws")
            val supplier = FakeRustSyncSecretSupplier()
            val executor = FakeRustSyncWorkExecutor()

            val worker =
                RustSyncWorker(
                    context,
                    params,
                    supplier,
                    executor,
                    stopProbe = { true },
                )
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
            executor.runCount shouldBe 0
        }
    }

    test("doWork cancels after lease issue still revokes and does not map executor result") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns
                RustSyncWorker.inputData(
                    backendKind = "hermetic_fake",
                    remoteDatasetId = "ds-worker",
                    workspaceRoot = "/ws",
                    secretFieldKey = "webdav_password",
                )
            val supplier = FakeRustSyncSecretSupplier()
            supplier.putLease("webdav_password", "lease-cancel")
            val executor = FakeRustSyncWorkExecutor()
            var stopAfterLease = false
            val worker =
                RustSyncWorker(
                    context,
                    params,
                    supplier,
                    executor,
                    stopProbe = { stopAfterLease },
                )
            // Flip stop after first issueLease by wrapping supplier is hard; use a probe that
            // becomes true once issueCount > 0 via a side channel on the fake.
            val gatedSupplier =
                object : RustSyncSecretSupplier {
                    override fun issueLease(
                        fieldKey: String,
                        ttlMillis: Long,
                    ): RemoteSyncSecretLease? {
                        val lease = supplier.issueLease(fieldKey, ttlMillis)
                        stopAfterLease = true
                        return lease
                    }

                    override fun revokeLease(leaseId: String) {
                        supplier.revokeLease(leaseId)
                    }
                }
            val gatedWorker =
                RustSyncWorker(
                    context,
                    params,
                    gatedSupplier,
                    executor,
                    stopProbe = { stopAfterLease },
                )
            val result = gatedWorker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
            executor.runCount shouldBe 0
            supplier.revokeCount shouldBe 1
            supplier.revokedIds shouldBe listOf("lease-cancel")
        }
    }

    test("doWork unexpected Exception maps to Transient without maxAttempts three") {
        runTest {
            val context: Context = mockk(relaxed = true)
            val params: WorkerParameters = mockk(relaxed = true)
            every { params.inputData } returns RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws")
            val supplier = FakeRustSyncSecretSupplier()
            val executor =
                FakeRustSyncWorkExecutor().apply {
                    throwError = IllegalStateException("host boom")
                }

            val worker = RustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        }
    }
})
