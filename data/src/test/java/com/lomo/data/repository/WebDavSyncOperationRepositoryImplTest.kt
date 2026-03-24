package com.lomo.data.repository

import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncStatus
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
 * - Unit under test: WebDavSyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, not-configured/error propagation, and status delegation.
 * - Observable outcomes: returned WebDavSyncResult/WebDavSyncStatus values and executor invocation counts.
 * - Excludes: WebDAV file-bridge planning logic, conflict modeling internals, and network client behavior.
 */
class WebDavSyncOperationRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var syncExecutor: WebDavSyncExecutor

    @MockK(relaxed = true)
    private lateinit var statusTester: WebDavSyncStatusTester

    private lateinit var repository: WebDavSyncOperationRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        repository =
            WebDavSyncOperationRepositoryImpl(
                syncExecutor = syncExecutor,
                statusTester = statusTester,
            )
    }

    @Test
    fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { syncExecutor.performSync() } returns WebDavSyncResult.NotConfigured

            val result = repository.sync()

            assertEquals(WebDavSyncResult.NotConfigured, result)
            coVerify(exactly = 1) { syncExecutor.performSync() }
        }

    @Test
    fun `sync short-circuits when another webdav sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { syncExecutor.performSync() } coAnswers {
                gate.await()
                WebDavSyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            assertEquals(WebDavSyncResult.Success("WebDAV sync already in progress"), secondCall)

            gate.complete(Unit)
            assertEquals(WebDavSyncResult.Success("sync done"), firstCall.await())
            coVerify(exactly = 1) { syncExecutor.performSync() }
        }

    @Test
    fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { syncExecutor.performSync() } throws IllegalStateException("sync failed") andThen WebDavSyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals("sync failed", firstFailure?.message)
            assertEquals(WebDavSyncResult.Success("recovered"), secondResult)
            coVerify(exactly = 2) { syncExecutor.performSync() }
        }

    @Test
    fun `getStatus delegates to status tester`() =
        runTest {
            val expected =
                WebDavSyncStatus(
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
            val expected = WebDavSyncResult.Error("connection failed")
            coEvery { statusTester.testConnection() } returns expected

            val result = repository.testConnection()

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusTester.testConnection() }
        }
}
