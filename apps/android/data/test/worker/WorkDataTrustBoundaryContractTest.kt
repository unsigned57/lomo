package com.lomo.data.worker

// adversarial-audit: persisted WorkData (queued + deferred-lock blob) must carry credential
// field references only; the worker leases whichever field name the persisted input supplies.

/*
 * Behavior Contract:
 * - Unit under test: sync worker WorkData persistence boundary.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: persisted WorkData carries credential field references only — never identity or
 *   lease material — and the worker leases whichever field name the persisted input supplies.
 *
 * Scenarios:
 * - Given a deferred WorkData blob, when round-tripped, then it carries field references only.
 * - Given persisted input naming a credential field, when the worker runs, then it leases that
 *   exact field.
 * - Given a locked session, when the blob is deferred, then input is unchanged and no extra keys
 *   leak into the blob.
 * - Given an oversized deferred blob, when decoded, then it is refused before materializing.
 *
 * Observable outcomes: persisted WorkData keys, leased credential fields, refusal decisions.
 *
 * TDD proof:
 * - The field-reference and refusal arms fail RED if identity/lease bytes or unbounded blobs are
 *   admitted into the persisted shape.
 *
 * Excludes:
 * - WorkManager scheduling internals and credential store encryption mechanics.
 */

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import android.content.Context
import com.lomo.data.engine.sync.RemoteSyncRetryDisposition
import com.lomo.data.engine.sync.RemoteSyncRetryHint
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import com.lomo.data.repository.AuthorizedCredentialReadSessionPolicy
import com.lomo.data.repository.LockedCredentialReadSessionPolicy
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class WorkDataTrustBoundaryContractTest : DataFunSpec() {
    init {
        test("deferred WorkData roundtrip carries field references only, never identity or lease") {
            val input =
                RustSyncWorker.inputData(
                    workspaceRoot = "/ws",
                    backendKind = "s3",
                    endpointUrl = "https://s3.example.invalid",
                    s3Bucket = "bucket",
                    s3Region = "region",
                    identityFieldKey = "S3_ACCESS_KEY_ID",
                    secretFieldKey = "S3_SECRET_ACCESS_KEY",
                )
            val store =
                FileDeferredLockWorkStore(
                    Files.createTempDirectory("workdata-trust").resolve("deferred.bin").toFile(),
                )
            store.save(input)

            val taken = store.take()!!

            // Every persisted key is an INPUT_* fact name — no plaintext secret, no identity value,
            // no lease id can ride the deferred blob.
            taken.keyValueMap.keys shouldContainExactlyInAnyOrder
                setOf(
                    RustSyncWorkRequest.INPUT_WORKSPACE_ROOT,
                    RustSyncWorkRequest.INPUT_BACKEND_KIND,
                    RustSyncWorkRequest.INPUT_ENDPOINT_URL,
                    RustSyncWorkRequest.INPUT_IDENTITY_FIELD_KEY,
                    RustSyncWorkRequest.INPUT_S3_BUCKET,
                    RustSyncWorkRequest.INPUT_S3_PREFIX,
                    RustSyncWorkRequest.INPUT_S3_REGION,
                    RustSyncWorkRequest.INPUT_GIT_BRANCH,
                    RustSyncWorkRequest.INPUT_GIT_AUTHOR_NAME,
                    RustSyncWorkRequest.INPUT_GIT_AUTHOR_EMAIL,
                    RustSyncWorkRequest.INPUT_REMOTE_DATASET_ID,
                    RustSyncWorkRequest.INPUT_SECRET_FIELD_KEY,
                    RustSyncWorkRequest.INPUT_LEASE_TTL_MILLIS,
                    RustSyncWorkRequest.INPUT_APPLY_REMOTE,
                    SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY,
                )
            val resolved = RustSyncWorker.resolveWorkRequest(taken)
            resolved.identity shouldBe ""
            resolved.secretLeaseId shouldBe null
            resolved.secretFieldKey shouldBe "S3_SECRET_ACCESS_KEY"
        }

        test("worker issues a lease for whichever credential field the persisted WorkData names") {
            runTest {
                // Trust-boundary documentation: nothing binds secretFieldKey to backendKind, so a
                // tampered (or mis-authored) persisted input cross-leases an unrelated credential
                // into the cycle — the sandbox file is the only integrity boundary.
                val input =
                    RustSyncWorker.inputData(
                        workspaceRoot = "/ws",
                        backendKind = "git",
                        endpointUrl = "https://git.example.invalid",
                        secretFieldKey = "S3_SECRET_ACCESS_KEY",
                    )
                val supplier = RecordingSecretSupplier(leases = mapOf("S3_SECRET_ACCESS_KEY" to "lease-s3"))
                val executor = RecordingWorkExecutor()
                val worker = worker(input, supplier, executor)

                val result = worker.doWork()

                result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
                supplier.issuedFields shouldBe listOf("S3_SECRET_ACCESS_KEY")
                executor.lastRequest?.secretLeaseId shouldBe "lease-s3"
            }
        }

        test("locked session defers input unchanged and leaks no extra keys into the blob") {
            runTest {
                val input =
                    RustSyncWorker.inputData(
                        workspaceRoot = "/ws",
                        backendKind = "webdav",
                        endpointUrl = "https://dav.example.invalid",
                        identityFieldKey = "WEBDAV_USERNAME",
                        secretFieldKey = "WEBDAV_PASSWORD",
                    )
                val deferred = InMemoryDeferredStore()
                val supplier = RecordingSecretSupplier()
                val executor = RecordingWorkExecutor()
                val worker =
                    worker(
                        input,
                        supplier,
                        executor,
                        session = LockedCredentialReadSessionPolicy,
                        deferred = deferred,
                    )

                val result = worker.doWork()

                result.shouldBeInstanceOf<ListenableWorker.Result.Success>()
                supplier.issuedFields shouldBe emptyList()
                executor.runCount shouldBe 0
                deferred.saved shouldBe input
            }
        }

        test("oversized deferred blob is refused before materializing") {
            val file = Files.createTempDirectory("workdata-big").resolve("deferred.bin").toFile()
            file.writeBytes(ByteArray(Data.MAX_DATA_BYTES + 1))
            val store = FileDeferredLockWorkStore(file)

            shouldThrow<java.io.IOException> { store.take() }
            file.exists() shouldBe false
        }
    }

    private class RecordingSecretSupplier(
        private val leases: Map<String, String> = emptyMap(),
        private val identities: Map<String, String> = emptyMap(),
    ) : RustSyncSecretSupplier {
        val issuedFields = mutableListOf<String>()

        override fun issueLease(
            fieldKey: String,
            ttlMillis: Long,
        ): RemoteSyncSecretLease? {
            issuedFields += fieldKey
            return leases[fieldKey]?.let { RemoteSyncSecretLease(leaseId = it) }
        }

        override fun revokeLease(leaseId: String) = Unit

        override fun identityUtf8(fieldKey: String): String? = identities[fieldKey]
    }

    private class RecordingWorkExecutor : RustSyncWorkExecutor {
        var runCount = 0
        var lastRequest: RustSyncWorkRequest? = null

        override suspend fun run(request: RustSyncWorkRequest): RemoteSyncRetryHint {
            runCount += 1
            lastRequest = request
            return RemoteSyncRetryHint(disposition = RemoteSyncRetryDisposition.AfterUserAction)
        }
    }

    private class InMemoryDeferredStore : DeferredLockWorkStore {
        var saved: Data? = null

        override fun save(input: Data) {
            saved = input
        }

        override fun take(): Data? = saved.also { saved = null }

        override fun clear() {
            saved = null
        }
    }

    private fun worker(
        input: Data,
        supplier: RustSyncSecretSupplier,
        executor: RustSyncWorkExecutor,
        session: com.lomo.domain.repository.SecuritySessionPolicy =
            AuthorizedCredentialReadSessionPolicy,
        deferred: DeferredLockWorkStore = InMemoryDeferredStore(),
    ): RustSyncWorker {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns input
        every { params.runAttemptCount } returns 0
        return RustSyncWorker(
            appContext = context,
            workerParams = params,
            secretSupplier = supplier,
            workExecutor = executor,
            securitySessionPolicy = session,
            engineReadiness = FakeEngineReadinessRepository(),
            deferredLockStore = deferred,
        )
    }
}
