package com.lomo.data.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lomo.data.repository.S3SyncWorkExecutor
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.S3SyncResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: S3SyncWorker.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: execute policy-selected S3 sync intent and map repository outcomes to WorkManager results using the retry budget encoded by scheduled sync work policy.
 *
 * Scenarios:
 * - Given periodic S3 work without explicit intent input, when the worker runs, then it executes the fast-only S3 sync lane.
 * - Given periodic S3 work with full-reconcile intent input, when the worker runs, then it executes the full-reconcile S3 sync lane.
 * - Given S3 sync returns a conflict, when the worker maps the result, then WorkManager receives success so conflict handling remains app/domain owned.
 * - Given S3 sync returns an error after the policy-owned attempt budget is exhausted, when the worker maps the result, then WorkManager receives failure instead of another infrastructure-default retry.
 *
 * Observable outcomes:
 * - returned ListenableWorker.Result and repository invocation count.
 *
 * TDD proof:
 * - RED: `:data:testDebugUnitTest --tests com.lomo.data.worker.S3SyncWorkerTest` fails before the retry/backoff fix because worker error handling ignores the policy-owned retry-attempt budget in input data.
 *
 * Excludes:
 * - WorkManager scheduling policy, Android process lifecycle, and S3 transport details.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class S3SyncWorkerTest : DataFunSpec() {
    init {
        test("doWork delegates periodic sync to shared s3 repository") { `doWork delegates periodic sync to shared s3 repository`() }

        test("doWork treats s3 conflict as successful periodic run") { `doWork treats s3 conflict as successful periodic run`() }

        test("doWork honors explicit full reconcile policy input") { `doWork honors explicit full reconcile policy input`() }

        test("doWork uses policy-owned retry budget for s3 errors") {
            `doWork uses policy-owned retry budget for s3 errors`()
        }
    }


    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val s3SyncExecutor: S3SyncWorkExecutor = mockk(relaxed = true)

    private fun `doWork delegates periodic sync to shared s3 repository`() =
        runTest {
            every { workerParams.inputData } returns workDataOf()
            coEvery { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FAST_ONLY) } returns
                S3SyncResult.Success("S3 sync completed")

            val worker = S3SyncWorker(context, workerParams, s3SyncExecutor)

            val result = worker.doWork()

            result shouldBe ListenableWorker.Result.success()
            coVerify(exactly = 1) { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FAST_ONLY) }
        }

    private fun `doWork treats s3 conflict as successful periodic run`() =
        runTest {
            every { workerParams.inputData } returns workDataOf()
            coEvery {
                s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FAST_ONLY)
            } returns S3SyncResult.Conflict(message = "conflict", conflicts = conflictSet())

            val worker = S3SyncWorker(context, workerParams, s3SyncExecutor)

            val result = worker.doWork()

            result shouldBe ListenableWorker.Result.success()
            coVerify(exactly = 1) { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FAST_ONLY) }
        }

    private fun `doWork honors explicit full reconcile policy input`() =
        runTest {
            every { workerParams.inputData } returns S3SyncWorker.inputData(S3SyncWorkIntent.FULL_RECONCILE)
            coEvery { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FULL_RECONCILE) } returns
                S3SyncResult.Success("reconciled")

            val worker = S3SyncWorker(context, workerParams, s3SyncExecutor)

            val result = worker.doWork()

            result shouldBe ListenableWorker.Result.success()
            coVerify(exactly = 1) { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FULL_RECONCILE) }
        }

    private fun `doWork uses policy-owned retry budget for s3 errors`() =
        runTest {
            every { workerParams.inputData } returns
                Data
                    .Builder()
                    .putAll(S3SyncWorker.inputData(S3SyncWorkIntent.FAST_ONLY))
                    .putInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, 2)
                    .build()
            every { workerParams.runAttemptCount } returns 2
            coEvery { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FAST_ONLY) } returns
                S3SyncResult.Error("retry budget exhausted")

            val worker = S3SyncWorker(context, workerParams, s3SyncExecutor)

            val result = worker.doWork()

            result shouldBe ListenableWorker.Result.failure()
            coVerify(exactly = 1) { s3SyncExecutor.executeS3Sync(S3SyncWorkIntent.FAST_ONLY) }
        }
}

private fun conflictSet() =
    com.lomo.domain.model.SyncConflictSet(
        source = com.lomo.domain.model.SyncBackendType.S3,
        files =
            listOf(
                com.lomo.domain.model.SyncConflictFile(
                    relativePath = "lomo/memo/note.md",
                    localContent = "local",
                    remoteContent = "remote",
                    isBinary = false,
                ),
            ),
        timestamp = 1L,
    )
