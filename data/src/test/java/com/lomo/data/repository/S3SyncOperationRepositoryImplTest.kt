package com.lomo.data.repository

import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncStatus
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, failure recovery, and status/test-connection delegation.
 * - Observable outcomes: returned S3SyncResult/S3SyncStatus values and executor invocation counts.
 * - Red phase: Fails before the fix because S3 sync still uses a placeholder repository implementation without executor/status-tester orchestration.
 * - Excludes: AWS transport behavior, file-bridge planning, conflict modeling internals, and UI rendering.
 */
class S3SyncOperationRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var syncExecutor: S3SyncExecutor

    @MockK(relaxed = true)
    private lateinit var statusTester: S3SyncStatusTester

    private lateinit var repository: S3SyncOperationRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            S3SyncOperationRepositoryImpl(
                syncExecutor = syncExecutor,
                statusTester = statusTester,
            )
    }

    @Test
    fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { syncExecutor.performSync() } returns S3SyncResult.NotConfigured

            val result = repository.sync()

            assertEquals(S3SyncResult.NotConfigured, result)
            coVerify(exactly = 1) { syncExecutor.performSync() }
        }

    @Test
    fun `sync short-circuits when another s3 sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { syncExecutor.performSync() } coAnswers {
                gate.await()
                S3SyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            assertEquals(S3SyncResult.Success("S3 sync already in progress"), secondCall)

            gate.complete(Unit)
            assertEquals(S3SyncResult.Success("sync done"), firstCall.await())
            coVerify(exactly = 1) { syncExecutor.performSync() }
        }

    @Test
    fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { syncExecutor.performSync() } throws IllegalStateException("sync failed") andThen
                S3SyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals("sync failed", firstFailure?.message)
            assertEquals(S3SyncResult.Success("recovered"), secondResult)
            coVerify(exactly = 2) { syncExecutor.performSync() }
        }

    @Test
    fun `getStatus delegates to status tester`() =
        runTest {
            val expected =
                S3SyncStatus(
                    remoteFileCount = 7,
                    localFileCount = 5,
                    pendingChanges = 2,
                    lastSyncTime = 456L,
                )
            coEvery { statusTester.getStatus() } returns expected

            val result = repository.getStatus()

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusTester.getStatus() }
        }

    @Test
    fun `testConnection delegates to status tester`() =
        runTest {
            val expected = S3SyncResult.Error("connection failed")
            coEvery { statusTester.testConnection() } returns expected

            val result = repository.testConnection()

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusTester.testConnection() }
        }
}
