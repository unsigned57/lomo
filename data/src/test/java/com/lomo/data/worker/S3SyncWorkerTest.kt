package com.lomo.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.repository.S3SyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncWorker
 * - Behavior focus: periodic S3 auto-sync delegates exactly once to the shared S3 sync repository and maps result types to WorkManager outcomes without opening another sync path.
 * - Observable outcomes: returned ListenableWorker.Result and repository invocation count.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: WorkManager scheduling policy, Android process lifecycle, and S3 transport details.
 */
class S3SyncWorkerTest {
    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val s3SyncRepository: S3SyncRepository = mockk(relaxed = true)

    @Test
    fun `doWork delegates periodic sync to shared s3 repository`() =
        runTest {
            coEvery { s3SyncRepository.sync() } returns S3SyncResult.Success("S3 sync completed")

            val worker = S3SyncWorker(context, workerParams, s3SyncRepository)

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { s3SyncRepository.sync() }
        }

    @Test
    fun `doWork treats s3 conflict as successful periodic run`() =
        runTest {
            coEvery {
                s3SyncRepository.sync()
            } returns S3SyncResult.Conflict(message = "conflict", conflicts = conflictSet())

            val worker = S3SyncWorker(context, workerParams, s3SyncRepository)

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { s3SyncRepository.sync() }
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
