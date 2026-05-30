package com.lomo.data.worker

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.WorkerParameters
import com.lomo.data.repository.S3SyncWorkExecutor
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.S3SyncResult
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncWorker
 * - Behavior focus: periodic S3 auto-sync delegates exactly once to the shared S3 sync repository and maps result types to WorkManager outcomes without opening another sync path.
 * - Observable outcomes: returned ListenableWorker.Result and repository invocation count.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: WorkManager scheduling policy, Android process lifecycle, and S3 transport details.
 */
class S3SyncWorkerTest : DataFunSpec() {
    init {
        test("doWork delegates periodic sync to shared s3 repository") { `doWork delegates periodic sync to shared s3 repository`() }

        test("doWork treats s3 conflict as successful periodic run") { `doWork treats s3 conflict as successful periodic run`() }

        test("doWork honors explicit full reconcile policy input") { `doWork honors explicit full reconcile policy input`() }
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
