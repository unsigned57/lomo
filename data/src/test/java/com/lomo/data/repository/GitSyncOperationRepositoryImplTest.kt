package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: GitSyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, disabled/not-configured propagation, and executor delegation.
 * - Observable outcomes: returned GitSyncResult/GitSyncStatus values and collaborator invocation counts.
 * - Excludes: git engine internals, SAF mirror behavior, and repository wiring outside this operation facade.
 */
class GitSyncOperationRepositoryImplTest {
    @MockK(relaxed = true)
    private lateinit var runtime: GitSyncRepositoryContext

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var initAndSyncExecutor: GitSyncInitAndSyncExecutor

    @MockK(relaxed = true)
    private lateinit var statusExecutor: GitSyncStatusExecutor

    @MockK(relaxed = true)
    private lateinit var maintenanceExecutor: GitSyncMaintenanceExecutor

    private lateinit var repository: GitSyncOperationRepositoryImpl

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { runtime.memoSynchronizer } returns memoSynchronizer
        every { runtime.dataStore } returns dataStore
        every { memoSynchronizer.outboxDrainCompleted } returns MutableSharedFlow()
        every { dataStore.gitSyncEnabled } returns flowOf(false)

        repository =
            GitSyncOperationRepositoryImpl(
                runtime = runtime,
                initAndSyncExecutor = initAndSyncExecutor,
                statusExecutor = statusExecutor,
                maintenanceExecutor = maintenanceExecutor,
            )
    }

    @Test
    fun `initOrClone delegates to executor result`() =
        runTest {
            val expected = GitSyncResult.Success("initialized")
            coEvery { initAndSyncExecutor.initOrClone() } returns expected

            val result = repository.initOrClone()

            assertEquals(expected, result)
            coVerify(exactly = 1) { initAndSyncExecutor.initOrClone() }
        }

    @Test
    fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { initAndSyncExecutor.sync() } returns GitSyncResult.NotConfigured

            val result = repository.sync()

            assertEquals(GitSyncResult.NotConfigured, result)
            coVerify(exactly = 1) { initAndSyncExecutor.sync() }
        }

    @Test
    fun `sync short-circuits when another sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { initAndSyncExecutor.sync() } coAnswers {
                gate.await()
                GitSyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            assertEquals(GitSyncResult.Success("Sync already in progress"), secondCall)

            gate.complete(Unit)
            assertEquals(GitSyncResult.Success("sync done"), firstCall.await())
            coVerify(exactly = 1) { initAndSyncExecutor.sync() }
        }

    @Test
    fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { initAndSyncExecutor.sync() } throws IllegalStateException("sync failed") andThen GitSyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals("sync failed", firstFailure?.message)
            assertEquals(GitSyncResult.Success("recovered"), secondResult)
            coVerify(exactly = 2) { initAndSyncExecutor.sync() }
        }

    @Test
    fun `getStatus delegates to status executor`() =
        runTest {
            val expected =
                GitSyncStatus(
                    hasLocalChanges = true,
                    aheadCount = 2,
                    behindCount = 1,
                    lastSyncTime = 123L,
                )
            coEvery { statusExecutor.getStatus() } returns expected

            val result = repository.getStatus()

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusExecutor.getStatus() }
        }

    @Test
    fun `testConnection delegates to status executor`() =
        runTest {
            val expected = GitSyncResult.Error("network down")
            coEvery { statusExecutor.testConnection() } returns expected

            val result = repository.testConnection()

            assertEquals(expected, result)
            coVerify(exactly = 1) { statusExecutor.testConnection() }
        }

    @Test
    fun `maintenance operations delegate to maintenance executor`() =
        runTest {
            coEvery { maintenanceExecutor.resetRepository() } returns GitSyncResult.Success("reset")
            coEvery { maintenanceExecutor.resetLocalBranchToRemote() } returns GitSyncResult.Success("hard reset")
            coEvery { maintenanceExecutor.forcePushLocalToRemote() } returns GitSyncResult.Success("force push")

            assertEquals(GitSyncResult.Success("reset"), repository.resetRepository())
            assertEquals(GitSyncResult.Success("hard reset"), repository.resetLocalBranchToRemote())
            assertEquals(GitSyncResult.Success("force push"), repository.forcePushLocalToRemote())

            coVerify(exactly = 1) { maintenanceExecutor.resetRepository() }
            coVerify(exactly = 1) { maintenanceExecutor.resetLocalBranchToRemote() }
            coVerify(exactly = 1) { maintenanceExecutor.forcePushLocalToRemote() }
        }
}
