package com.lomo.data.repository

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



import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncOperationRepositoryImpl
 * - Behavior focus: sync guard short-circuiting, not-configured/error propagation, and status delegation.
 * - Observable outcomes: returned WebDavSyncResult/WebDavSyncStatus values and executor invocation counts.
 * - TDD proof: Fails before the fix when pending WebDAV conflicts are not restored ahead of a new sync attempt.
 * - Excludes: WebDAV file-bridge planning logic, conflict modeling internals, and network client behavior.
 */
class WebDavSyncOperationRepositoryImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("sync propagates not-configured result from executor") { `sync propagates not-configured result from executor`() }

        test("sync short-circuits when another webdav sync is in progress") { `sync short-circuits when another webdav sync is in progress`() }

        test("sync releases guard after failure so a later sync can run") { `sync releases guard after failure so a later sync can run`() }

        test("getStatus delegates to status tester") { `getStatus delegates to status tester`() }

        test("testConnection delegates to status tester") { `testConnection delegates to status tester`() }

        test("sync restores pending webdav conflicts from store before invoking executor") { `sync restores pending webdav conflicts from store before invoking executor`() }
    }


    /*
     * Test Change Justification:
     * - Reason category: mechanical reshaping after adding pending-conflict persistence.
     * - Replaced assertion/setup: implicit relaxed mock behavior for PendingSyncConflictStore.
     * - Previous setup is no longer correct because sync now always checks the store before invoking the executor.
     * - Retained coverage: existing tests still prove executor delegation, sync-guard behavior, and explicit pending-conflict restoration.
     * - This is not changing the test to fit the implementation; it makes the pre-existing "no pending conflicts" baseline explicit.
     */
    @MockK(relaxed = true)
    private lateinit var syncExecutor: WebDavSyncExecutor

    @MockK(relaxed = true)
    private lateinit var statusTester: WebDavSyncStatusTester

    @MockK(relaxed = true)
    private lateinit var pendingConflictStore: PendingSyncConflictStore

    private lateinit var stateHolder: WebDavSyncStateHolder

    private lateinit var repository: WebDavSyncOperationRepositoryImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        stateHolder = WebDavSyncStateHolder()
        coEvery { pendingConflictStore.readDescriptor(SyncBackendType.WEBDAV) } returns null
        repository =
            WebDavSyncOperationRepositoryImpl(
                syncExecutor = syncExecutor,
                statusTester = statusTester,
                pendingConflictStore = pendingConflictStore,
                stateHolder = stateHolder,
            )
    }

    private fun `sync propagates not-configured result from executor`() =
        runTest {
            coEvery { syncExecutor.performSync() } returns WebDavSyncResult.NotConfigured

            val result = repository.sync()

            result shouldBe WebDavSyncResult.NotConfigured
            coVerify(exactly = 1) { syncExecutor.performSync() }
        }

    private fun `sync short-circuits when another webdav sync is in progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { syncExecutor.performSync() } coAnswers {
                gate.await()
                WebDavSyncResult.Success("sync done")
            }

            val firstCall = async { repository.sync() }
            kotlinx.coroutines.yield()
            val secondCall = repository.sync()

            secondCall shouldBe WebDavSyncResult.Success("WebDAV sync already in progress")

            gate.complete(Unit)
            firstCall.await() shouldBe WebDavSyncResult.Success("sync done")
            coVerify(exactly = 1) { syncExecutor.performSync() }
        }

    private fun `sync releases guard after failure so a later sync can run`() =
        runTest {
            coEvery { syncExecutor.performSync() } throws IllegalStateException("sync failed") andThen WebDavSyncResult.Success("recovered")

            val firstFailure =
                runCatching {
                    repository.sync()
                }.exceptionOrNull()
            val secondResult = repository.sync()

            (firstFailure is IllegalStateException).shouldBeTrue()
            firstFailure?.message shouldBe "sync failed"
            secondResult shouldBe WebDavSyncResult.Success("recovered")
            coVerify(exactly = 2) { syncExecutor.performSync() }
        }

    private fun `getStatus delegates to status tester`() =
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

            result shouldBe expected
            coVerify(exactly = 1) { statusTester.getStatus() }
        }

    private fun `testConnection delegates to status tester`() =
        runTest {
            val expected = WebDavSyncResult.Error("connection failed")
            coEvery { statusTester.testConnection() } returns expected

            val result = repository.testConnection()

            result shouldBe expected
            coVerify(exactly = 1) { statusTester.testConnection() }
        }

    private fun `sync restores pending webdav conflicts from store before invoking executor`() =
        runTest {
            val pending =
                SyncConflictSet(
                    source = SyncBackendType.WEBDAV,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/note.md",
                                localContent = "local",
                                remoteContent = "remote",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 321L,
                )
            coEvery { pendingConflictStore.readDescriptor(SyncBackendType.WEBDAV) } returns pending.toPendingDescriptor()
            coEvery { syncExecutor.restorePendingConflict(any()) } returns PendingSyncRestoreResult.Restored(pending)

            val result = repository.sync()

            result shouldBe WebDavSyncResult.Conflict("Pending conflicts remain", pending)
            coVerify(exactly = 0) { syncExecutor.performSync() }
        }
}
