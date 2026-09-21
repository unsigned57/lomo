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
 * - Given locked security session, when doWork runs, then Result.success without executor or lease,
 *   and the WorkData is saved for deferred resume (not retry, not failure).
 * - Given authorized session, when doWork runs, then executor runs and nothing is deferred.
 * - Given Transient after the WorkData maxAttempts ceiling, when doWork runs, then Result.failure
 *   (terminal) rather than Result.retry.
 *
 * Observable outcomes: ListenableWorker.Result type; lease issue/revoke counts; executor request
 * fields; optional delay Long?; consumed maxAttempts.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.RustSyncWorkerTest'
 * - RED: `doWork Transient over retry budget` expected Failure but was Retry (unbounded Result.retry).
 * - GREEN: same spec; Transient under ceiling still retries.
 *
 * Test Change Justification:
 * - Task: T32 deferred-lock retry ceiling.
 * - Reason category: Domain contract change.
 * - Old behavior/assertion being replaced: Transient always mapped to Result.retry regardless of
 *   WorkData maxAttempts / runAttemptCount.
 * - Why old assertion is no longer correct: B03 requires consuming the retry ceiling; unbounded
 *   retry is the defect.
 * - Coverage preserved by: Transient at attempt 0 still retries; attempt >= maxAttempts is Failure.
 * - Why this is not fitting the test to the implementation: terminal over-budget is the product law.
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
import com.lomo.data.repository.AuthorizedCredentialReadSessionPolicy
import com.lomo.data.repository.LockedCredentialReadSessionPolicy
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
    private val identitiesByField: MutableMap<String, String> = mutableMapOf()

    fun putLease(
        fieldKey: String,
        leaseId: String,
    ) {
        leasesByField[fieldKey] = leaseId
    }

    fun putIdentity(
        fieldKey: String,
        value: String,
    ) {
        identitiesByField[fieldKey] = value
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

    override fun identityUtf8(fieldKey: String): String? = identitiesByField[fieldKey]
}

private class InMemoryDeferredLockWorkStore : DeferredLockWorkStore {
    var saved: androidx.work.Data? = null
        private set

    override fun save(input: androidx.work.Data) {
        saved = input
    }

    override fun take(): androidx.work.Data? = saved.also { saved = null }
}

private fun rustSyncWorker(
    context: Context,
    params: WorkerParameters,
    supplier: RustSyncSecretSupplier,
    executor: RustSyncWorkExecutor,
    session: com.lomo.domain.repository.SecuritySessionPolicy = AuthorizedCredentialReadSessionPolicy,
    deferred: DeferredLockWorkStore = InMemoryDeferredLockWorkStore(),
    stopProbe: () -> Boolean = { false },
): RustSyncWorker =
    RustSyncWorker(
        appContext = context,
        workerParams = params,
        secretSupplier = supplier,
        workExecutor = executor,
        securitySessionPolicy = session,
        deferredLockStore = deferred,
        stopProbe = stopProbe,
    )

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

    test("Transient maps to retry under budget and preserves positive retryAfter") {
        val hint =
            RemoteSyncRetryHint(
                disposition = RemoteSyncRetryDisposition.Transient,
                retryAfterMillis = 12_000,
            )
        RustSyncRetryPolicy
            .workResult(hint, runAttemptCount = 0, maxAttempts = 3)
            .shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        RustSyncRetryPolicy
            .workResult(hint, runAttemptCount = 3, maxAttempts = 3)
            .shouldBeInstanceOf<ListenableWorker.Result.Failure>()
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
            val workerParams = RustSyncWorkerParamsFixture(workDataOf())
            val context = workerParams.context
            val params = workerParams.params
            val supplier = FakeRustSyncSecretSupplier()
            val executor = FakeRustSyncWorkExecutor()

            val worker = rustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            executor.runCount shouldBe 0
            supplier.issueCount shouldBe 0
        }
    }

    test("doWork fail-closed when required secret lease is missing") {
        runTest {
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(
                        backendKind = "hermetic_fake",
                        remoteDatasetId = "ds-worker",
                        workspaceRoot = "/ws",
                        secretFieldKey = "webdav_password",
                    ),
                )
            val context = workerParams.context
            val params = workerParams.params
            val supplier = FakeRustSyncSecretSupplier() // no putLease → null lease
            val executor = FakeRustSyncWorkExecutor()

            val worker = rustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            executor.runCount shouldBe 0
            supplier.issueCount shouldBe 1
            supplier.revokeCount shouldBe 0
        }
    }

    test("doWork issues lease, runs executor, maps Transient, and always revokes") {
        runTest {
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(
                        backendKind = "hermetic_fake",
                        remoteDatasetId = "ds-worker",
                        workspaceRoot = "/ws",
                        secretFieldKey = "webdav_password",
                        leaseTtlMillis = 30_000,
                    ),
                )
            val context = workerParams.context
            val params = workerParams.params
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

            val worker = rustSyncWorker(context, params, supplier, executor)
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
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws"),
                )
            val context = workerParams.context
            val params = workerParams.params
            val supplier = FakeRustSyncSecretSupplier()
            val executor =
                FakeRustSyncWorkExecutor(
                    hint = RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.AfterUserAction),
                )

            val worker = rustSyncWorker(context, params, supplier, executor)
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
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(
                        backendKind = "hermetic_fake",
                        remoteDatasetId = "ds-worker",
                        workspaceRoot = "/ws",
                        secretFieldKey = "s3_secret",
                    ),
                )
            val context = workerParams.context
            val params = workerParams.params
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

            val worker = rustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
            supplier.revokeCount shouldBe 1
            supplier.revokedIds shouldBe listOf("lease-boundary")
        }
    }

    test("doWork maps unknown boundary disposition to Never fail-closed") {
        runTest {
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws"),
                )
            val context = workerParams.context
            val params = workerParams.params
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

            val worker = rustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
        }
    }

    test("doWork returns success without running when stop probe is set") {
        runTest {
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws"),
                )
            val context = workerParams.context
            val params = workerParams.params
            val supplier = FakeRustSyncSecretSupplier()
            val executor = FakeRustSyncWorkExecutor()

            val worker =
                rustSyncWorker(
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
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(
                        backendKind = "hermetic_fake",
                        remoteDatasetId = "ds-worker",
                        workspaceRoot = "/ws",
                        secretFieldKey = "webdav_password",
                    ),
                )
            val context = workerParams.context
            val params = workerParams.params
            val supplier = FakeRustSyncSecretSupplier()
            supplier.putLease("webdav_password", "lease-cancel")
            val executor = FakeRustSyncWorkExecutor()
            var stopAfterLease = false
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

                    override fun identityUtf8(fieldKey: String): String? = supplier.identityUtf8(fieldKey)
                }
            val gatedWorker =
                rustSyncWorker(
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

    test("doWork Transient over retry budget is terminal failure not infinite retry") {
        runTest {
            val input =
                androidx.work.Data
                    .Builder()
                    .putAll(
                        RustSyncWorker.inputData(
                            backendKind = "hermetic_fake",
                            workspaceRoot = "/ws",
                        ),
                    ).putInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, 3)
                    .build()
            val workerParams = RustSyncWorkerParamsFixture(input, runAttemptCount = 3)
            val supplier = FakeRustSyncSecretSupplier()
            val executor =
                FakeRustSyncWorkExecutor(
                    RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.Transient),
                )

            val result =
                rustSyncWorker(
                    workerParams.context,
                    workerParams.params,
                    supplier,
                    executor,
                ).doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Failure>()
            executor.runCount shouldBe 1
        }
    }

    test("doWork unexpected Exception maps to Transient without maxAttempts three") {
        runTest {
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(backendKind = "hermetic_fake", workspaceRoot = "/ws"),
                )
            val context = workerParams.context
            val params = workerParams.params
            val supplier = FakeRustSyncSecretSupplier()
            val executor =
                FakeRustSyncWorkExecutor().apply {
                    throwError = IllegalStateException("host boom")
                }

            val worker = rustSyncWorker(context, params, supplier, executor)
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Retry>()
        }
    }

    test("work input must not persist username or access key plaintext") {
        val secretIdentity = "  test-access-key-secret  "
        val data =
            RustSyncWorker.inputData(
                workspaceRoot = "/ws",
                backendKind = "s3",
                identityFieldKey = "S3_ACCESS_KEY_ID",
                secretFieldKey = "S3_SECRET_ACCESS_KEY",
            )

        data.keyValueMap.values.none { value -> value.toString() == secretIdentity } shouldBe true
        data.keyValueMap.keys.none { key -> key.contains("username") || key.contains("access_key") } shouldBe true
        data.getString(RustSyncWorkRequest.INPUT_IDENTITY_FIELD_KEY) shouldBe "S3_ACCESS_KEY_ID"
        data.getInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, 0) shouldBe
            com.lomo.data.sync.REMOTE_AUTO_SYNC_RETRY_POLICY.maxAttempts
    }

    test("doWork copies identity utf8 including surrounding spaces without writing it to input data") {
        runTest {
            val identity = "  spaced-user  "
            val workerParams =
                RustSyncWorkerParamsFixture(
                    RustSyncWorker.inputData(
                        backendKind = "webdav",
                        workspaceRoot = "/ws",
                        identityFieldKey = "WEBDAV_USERNAME",
                        secretFieldKey = "WEBDAV_PASSWORD",
                    ),
                )
            val supplier = FakeRustSyncSecretSupplier()
            supplier.putIdentity("WEBDAV_USERNAME", identity)
            supplier.putLease("WEBDAV_PASSWORD", "lease-id")
            val executor = FakeRustSyncWorkExecutor()

            val worker = rustSyncWorker(workerParams.context, workerParams.params, supplier, executor)
            worker.doWork()

            executor.lastRequest?.usernameOrAccessKey shouldBe identity
            workerParams.params.inputData.keyValueMap.values.none { value ->
                value.toString() == identity
            } shouldBe true
        }
    }

    test("doWork defers locked session without running executor or issuing lease") {
        runTest {
            val input =
                RustSyncWorker.inputData(
                    backendKind = "webdav",
                    workspaceRoot = "/ws",
                    secretFieldKey = "WEBDAV_PASSWORD",
                )
            val workerParams = RustSyncWorkerParamsFixture(input)
            val supplier = FakeRustSyncSecretSupplier()
            supplier.putLease("WEBDAV_PASSWORD", "lease-locked")
            val executor = FakeRustSyncWorkExecutor()
            val deferred = InMemoryDeferredLockWorkStore()

            val worker =
                rustSyncWorker(
                    workerParams.context,
                    workerParams.params,
                    supplier,
                    executor,
                    session = LockedCredentialReadSessionPolicy,
                    deferred = deferred,
                )
            val result = worker.doWork()

            result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
            executor.runCount shouldBe 0
            supplier.issueCount shouldBe 0
            deferred.saved shouldBe input
        }
    }
})

private class RustSyncWorkerParamsFixture(
    input: androidx.work.Data,
    runAttemptCount: Int = 0,
) {
    val context: Context = mockk(relaxed = true)
    val params: WorkerParameters = mockk(relaxed = true)

    init {
        every { params.inputData } returns input
        every { params.runAttemptCount } returns runAttemptCount
    }
}
